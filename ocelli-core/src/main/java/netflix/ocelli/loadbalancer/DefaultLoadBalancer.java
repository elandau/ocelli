package netflix.ocelli.loadbalancer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import netflix.ocelli.ClientAndMetrics;
import netflix.ocelli.ClientConnector;
import netflix.ocelli.FailureDetectorFactory;
import netflix.ocelli.ManagedLoadBalancer;
import netflix.ocelli.MembershipEvent;
import netflix.ocelli.MembershipEvent.EventType;
import netflix.ocelli.MetricsFactory;
import netflix.ocelli.WeightingStrategy;
import netflix.ocelli.algorithm.EqualWeightStrategy;
import netflix.ocelli.functions.Connectors;
import netflix.ocelli.functions.Delays;
import netflix.ocelli.functions.Failures;
import netflix.ocelli.functions.Functions;
import netflix.ocelli.selectors.ClientsAndWeights;
import netflix.ocelli.selectors.RoundRobinSelectionStrategy;
import netflix.ocelli.util.RandomBlockingQueue;
import netflix.ocelli.util.RxUtil;
import netflix.ocelli.util.StateMachine;
import netflix.ocelli.util.StateMachine.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.SerialSubscription;

/**
 * The ClientSelector keeps track of all existing hosts and returns a single host for each
 * call to acquire().
 * 
 * @author elandau
 *
 * @param <C>
 * @param <Tracker>
 * 
 */
public class DefaultLoadBalancer<C, M> implements ManagedLoadBalancer<C> {
    
    private static final Logger LOG = LoggerFactory.getLogger(DefaultLoadBalancer.class);
    
    /**
     * Da Builder 
     * @author elandau
     *
     * @param <C>
     * @param <C>
     * @param <Tracker>
     */
    public static class Builder<C, M> {
        private Observable<MembershipEvent<C>>   hostSource;
        private String                      name = "<unnamed>";
        private WeightingStrategy<C, M>     weightingStrategy = new EqualWeightStrategy<C, M>();
        private Func1<Integer, Integer>     connectedHostCountStrategy = Functions.identity();
        private Func1<Integer, Long>        quaratineDelayStrategy = Delays.fixed(10, TimeUnit.SECONDS);
        private Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy = new RoundRobinSelectionStrategy<C>();
        private FailureDetectorFactory<C>   failureDetector = Failures.never();
        private ClientConnector<C>          clientConnector = Connectors.immediate();
        private Func1<C, Observable<M>>     metricsMapper;
        
        private Builder() {
        }
        
        /**
         * Arbitrary name assigned to the connection pool, mostly for debugging purposes
         * @param name
         */
        public Builder<C, M> withName(String name) {
            this.name = name;
            return this;
        }
        
        /**
         * Strategy used to determine the delay time in msec based on the quarantine 
         * count.  The count is incremented by one for each failure detections and reset
         * once the host is back to normal.
         */
        public Builder<C, M> withQuaratineStrategy(Func1<Integer, Long> quaratineDelayStrategy) {
            this.quaratineDelayStrategy = quaratineDelayStrategy;
            return this;
        }
        
        /**
         * Strategy used to determine how many hosts should be active.
         * This strategy is invoked whenever a host is added or removed from the pool
         */
        public Builder<C, M> withActiveClientCountStrategy(Func1<Integer, Integer> activeClientCountStrategy) {
            this.connectedHostCountStrategy = activeClientCountStrategy;
            return this;
        }
        
        /**
         * Source for host membership events
         */
        public Builder<C, M> withMembershipSource(Observable<MembershipEvent<C>> hostSource) {
            this.hostSource = hostSource;
            return this;
        }
        
        /**
         * Strategy use to calculate weights for active clients
         */
        public Builder<C, M> withWeightingStrategy(WeightingStrategy<C, M> algorithm) {
            this.weightingStrategy = algorithm;
            return this;
        }
        
        /**
         * Strategy used to select hosts from the calculated weights.  
         * @param selectionStrategy
         */
        public Builder<C, M> withSelectionStrategy(Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy) {
            this.selectionStrategy = selectionStrategy;
            return this;
        }
        
        /**
         * The failure detector returns an Observable that will emit a Throwable for each 
         * failure of the client.  The load balancer will quaratine the client in response.
         * @param failureDetector
         */
        public Builder<C, M> withFailureDetector(FailureDetectorFactory<C> failureDetector) {
            this.failureDetector = failureDetector;
            return this;
        }
        
        /**
         * The connector can be used to prime a client prior to activating it in the connection
         * pool.  
         * @param clientConnector
         */
        public Builder<C, M> withClientConnector(ClientConnector<C> clientConnector) {
            this.clientConnector = clientConnector;
            return this;
        }
        
        /**
         * Factory for creating and associating the metrics used for weighting with a client.
         * Note that for robust client interface C and M may be the same client type and the 
         * factory will simply return an Observable.just(client);
         * 
         * @param metricsMapper
         * @return
         */
        public Builder<C, M> withMetricsFactory(MetricsFactory<C, M> metricsMapper) {
            this.metricsMapper = metricsMapper;
            return this;
        }
        
        public DefaultLoadBalancer<C, M> build() {
            assert hostSource != null;
            assert metricsMapper != null;
            
            return new DefaultLoadBalancer<C, M>(this);
        }
    }
    
    public static <C, M> Builder<C, M> builder() {
        return new Builder<C, M>();
    }
    
    private final Observable<MembershipEvent<C>> hostSource;
    private final WeightingStrategy<C, M> weightingStrategy;
    private final FailureDetectorFactory<C> failureDetector;
    private final ClientConnector<C> clientConnector;
    private final Func1<Integer, Integer> connectedHostCountStrategy;
    private final Func1<Integer, Long> quaratineDelayStrategy;
    private final Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy;
    private final Func1<C, Observable<M>> metricsMapper;
    
    private final String name;

    /**
     * Composite subscription to keep track of all Subscriptions to be unsubscribed at
     * shutdown
     */
    private final CompositeSubscription cs = new CompositeSubscription();
    
    /**
     * Map of ALL existing hosts, connected or not
     */
    private final ConcurrentMap<C, Holder> clients = new ConcurrentHashMap<C, Holder>();
    
    /**
     * Queue of idle hosts that are not initialized to receive traffic
     */
    private final RandomBlockingQueue<Holder> idleClients = new RandomBlockingQueue<Holder>();
    
    /**
     * Map of ALL currently acquired clients.  This map contains both connected as well as connecting hosts.
     */
    private final Set<Holder> acquiredClients = new HashSet<Holder>();
    
    /**
     * Array of active and healthy clients that can receive traffic
     */
    private final CopyOnWriteArrayList<ClientAndMetrics<C, M>> activeClients = new CopyOnWriteArrayList<ClientAndMetrics<C, M>>();
    
    private State<Holder, EventType> IDLE         = State.create("IDLE");
    private State<Holder, EventType> CONNECTING   = State.create("CONNECTING");
    private State<Holder, EventType> CONNECTED    = State.create("CONNECTED");
    private State<Holder, EventType> QUARANTINED  = State.create("QUARANTINED");
    private State<Holder, EventType> REMOVED      = State.create("REMOVED");

    private DefaultLoadBalancer(Builder<C, M> builder) {
        this.weightingStrategy          = builder.weightingStrategy;
        this.connectedHostCountStrategy = builder.connectedHostCountStrategy;
        this.quaratineDelayStrategy     = builder.quaratineDelayStrategy;
        this.selectionStrategy          = builder.selectionStrategy;
        this.name                       = builder.name;
        this.failureDetector            = builder.failureDetector;
        this.clientConnector            = builder.clientConnector;
        this.hostSource                 = builder.hostSource;
        this.metricsMapper              = builder.metricsMapper;
    }

    public void initialize() {
        
        IDLE
            .onEnter(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(Holder holder) {
                    LOG.info("{} - {} is idle", name, holder.getClient());
                    
                    idleClients.add(holder);
    
                    // Determine if a new host should be created based on the configured strategy
                    int idealCount = connectedHostCountStrategy.call(clients.size());
                    if (idealCount > acquiredClients.size()) {
                        acquireNextIdleHost()  
                            .first()
                            .subscribe(new Action1<Holder>() {
                                @Override
                                public void call(Holder holder) {
                                    holder.sm.call(EventType.CONNECT);
                                }
                            });
                    }
                    return Observable.empty();
                }
            })
            .transition(EventType.CONNECT, CONNECTING)
            .transition(EventType.FAILED, QUARANTINED)
            .transition(EventType.CONNECTED, CONNECTED)
            ;
        
        CONNECTING
            .onEnter(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(final Holder holder) {
                    LOG.info("{} - {} is connecting", name, holder.getClient());
                    acquiredClients.add(holder);
                    holder.connect();
                    return Observable.empty();
                }
            })
            .transition(EventType.CONNECTED, CONNECTED)
            .transition(EventType.FAILED, QUARANTINED)
            .transition(EventType.REMOVE, REMOVED)
            ;
        
        CONNECTED
            .onEnter(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(Holder holder) {
                    LOG.info("{} - {} is connected", name, holder.getClient());
                    activeClients.add(holder);
                    return Observable.empty();
                }
            })
            .onExit(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(Holder holder) {
                    activeClients.remove(holder);
                    return Observable.empty();
                }
            })
            .ignore(EventType.CONNECTED)
            .ignore(EventType.CONNECT)
            .transition(EventType.FAILED, QUARANTINED)
            .transition(EventType.REMOVE, REMOVED)
            .transition(EventType.STOP, IDLE)
            ;
        
        QUARANTINED
            .onEnter(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(final Holder holder) {
                    LOG.info("{} - {} is quaratined ({})", name, holder.getClient(), holder.quaratineCounter);
                    acquiredClients.remove(holder);
                    
                    return Observable
                            .just(EventType.UNQUARANTINE)
                            .delay(quaratineDelayStrategy.call(holder.getQuaratineCounter()), TimeUnit.MILLISECONDS)
                            .doOnNext(RxUtil.info("Next:")); 
                }
            })
            .ignore(EventType.FAILED)
            .transition(EventType.UNQUARANTINE, IDLE)
            .transition(EventType.REMOVE, REMOVED)
            .transition(EventType.CONNECTED, CONNECTED)
            ;
        
        REMOVED
            .onEnter(new Func1<Holder, Observable<EventType>>() {
                @Override
                public Observable<EventType> call(Holder holder) {
                    LOG.info("{} - {} is removed", name, holder.getClient());
                    activeClients.remove(holder);
                    acquiredClients.add(holder);
                    idleClients.remove(holder);
                    clients.remove(holder.client);
                    cs.remove(holder.cs);
                    return Observable.empty();
                }
        })
        ;
        cs.add(hostSource
            .subscribe(new Action1<MembershipEvent<C>>() {
                @Override
                public void call(MembershipEvent<C> event) {
                    Holder holder = clients.get(event.getClient());
                    if (holder == null) {
                        if (event.getType().equals(EventType.ADD)) {
                            final Holder newHolder = new Holder(event.getClient(), IDLE);
                            if (null == clients.putIfAbsent(event.getClient(), newHolder)) {
                                LOG.trace("{} - {} is added", name, newHolder.getClient());
                                newHolder.initialize();
                            }
                        }
                    }
                    else {
                        holder.sm.call(event.getType());
                    }
                }
            }));
    }
    
    /**
     * Holder the client state within the context of this LoadBalancer
     */
    public class Holder implements ClientAndMetrics<C, M> {
        final AtomicInteger quaratineCounter = new AtomicInteger();
        final C client;
        volatile M metrics;
        final StateMachine<Holder, EventType> sm;
        final CompositeSubscription cs = new CompositeSubscription();
        final SerialSubscription connectSubscription = new SerialSubscription();
        
        public Holder(C client, State<Holder, EventType> initial) {
            this.client = client;
            this.sm = StateMachine.create(this, initial);
        }
        
        public void initialize() {
            this.cs.add(sm.start().subscribe());
            this.cs.add(connectSubscription);
            this.cs.add(metricsMapper.call(client).subscribe(new Action1<M>() {
                @Override
                public void call(M t1) {
                    metrics = t1;
                }
            }));
            this.cs.add(failureDetector.call(client).subscribe(new Action1<Throwable>() {
                @Override
                public void call(Throwable t1) {
                    sm.call(EventType.FAILED);
                    quaratineCounter.incrementAndGet();
                }
            }));
        }
        
        public void connect() {
            connectSubscription.set(clientConnector.call(client).subscribe(
                new Action1<C>() {
                    @Override
                    public void call(C client) {
                        sm.call(EventType.CONNECTED);
                        quaratineCounter.set(0);
                    }
                },
                new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t1) {
                        sm.call(EventType.FAILED);
                        quaratineCounter.incrementAndGet();
                    }
                }));
        }
        
        public int getQuaratineCounter() {
            return quaratineCounter.get();
        }

        public void shutdown() {
            cs.unsubscribe();
        }
        
        public String toString() {
            return "Holder[" + name + "-" + client + "]";
        }

        @Override
        public C getClient() {
            return client;
        }

        @Override
        public M getMetrics() {
            return metrics;
        }
    }
    
    public void shutdown() {
        cs.unsubscribe();
    }
    
    /**
     * @return Return the next idle host or empty() if none available
     */
    private Observable<Holder> acquireNextIdleHost() {
        return Observable.create(new OnSubscribe<Holder>() {
            @Override
            public void call(Subscriber<? super Holder> s) {
                try {
                    Holder holder = idleClients.poll();
                    if (holder != null)
                        s.onNext(holder);
                    s.onCompleted();
                }
                catch (Exception e) {
                    s.onError(e);
                }
            }
        });
    }
    
    /**
     * Acquire the most recent list of hosts
     */
    @Override
    public Observable<C> choose() {
        return selectionStrategy.call(
                    weightingStrategy.call(new ArrayList<ClientAndMetrics<C, M>>(activeClients)));
    }

    @Override
    public Observable<C> listAllClients() {
        return Observable.from(new HashSet<C>(clients.keySet()));
    }
    
    @Override
    public Observable<C> listActiveClients() {
        return Observable.from(activeClients).map(new Func1<ClientAndMetrics<C,M>, C>() {
            @Override
            public C call(ClientAndMetrics<C, M> t1) {
                return t1.getClient();
            }
        });
    }
    
    public String getName() {
        return name;
    }
    
    public String toString() {
        return "DefaultLoadBalancer[" + name + "]";
    }
}