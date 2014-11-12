package netflix.ocelli.client;

import java.util.concurrent.ConcurrentMap;

import com.google.common.collect.Maps;

import rx.Observable;
import rx.subjects.PublishSubject;
import netflix.ocelli.FailureDetectorFactory;

public class ManualFailureDetector implements FailureDetectorFactory<TestClient> {
    private ConcurrentMap<TestClient, PublishSubject<Throwable>> clients = Maps.newConcurrentMap();
    
    @Override
    public Observable<Throwable> call(TestClient client) {
        PublishSubject<Throwable> subject = PublishSubject.create();
        clients.putIfAbsent(client, subject);
        return subject;
    }
    
    public PublishSubject<Throwable> get(TestClient client) {
        return clients.get(client);
    }

}
