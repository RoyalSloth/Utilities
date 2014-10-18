package dorkbox.util.objectPool;

import dorkbox.util.Sys;

public class ObjectPoolFactory {

    private ObjectPoolFactory() {
    }

    /**
     * Creates a pool with the max number of available processors as the pool size (padded by 2x as many).
     */
    public static <T> ObjectPool<T> create(PoolableObject<T> poolableObject) {
        return create(poolableObject, Runtime.getRuntime().availableProcessors() * 2);
    }


    /**
     * Creates a pool of the specified size
     */
    public static <T> ObjectPool<T> create(PoolableObject<T> poolableObject, int size) {
        if (Sys.isAndroid) {
            // unfortunately, unsafe is not available in android
            SlowObjectPool<T> slowObjectPool = new SlowObjectPool<T>(poolableObject, size);
            return slowObjectPool;
        } else {
            // here we use FAST (via UNSAFE) one!
            FastObjectPool<T> fastObjectPool = new FastObjectPool<T>(poolableObject, size);
            return fastObjectPool;
        }
    }
}