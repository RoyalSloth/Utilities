/*
 * Copyright 2014 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.storage;

import dorkbox.util.DelayTimer;
import dorkbox.util.SerializationManager;
import dorkbox.util.bytes.ByteArrayWrapper;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Nothing spectacular about this storage -- it allows for persistent storage of objects to disk.
 * <p/>
 * Be wary of opening the database file in different JVM instances. Even with file-locks, you can corrupt the data.
 */
@SuppressWarnings("unused")
public
class DiskStorage implements Storage {
    private final DelayTimer timer;
    private final ByteArrayWrapper defaultKey;
    private final StorageBase storage;

    private final AtomicInteger references = new AtomicInteger(1);
    private final ReentrantLock actionLock = new ReentrantLock();
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private volatile long milliSeconds = 3000L;

    private volatile Map<ByteArrayWrapper, Object> actionMap = new ConcurrentHashMap<ByteArrayWrapper, Object>();

    /**
     * Creates or opens a new database file.
     */
    protected
    DiskStorage(File storageFile, SerializationManager serializationManager, final boolean readOnly) throws IOException {
        this.storage = new StorageBase(storageFile, serializationManager);
        this.defaultKey = ByteArrayWrapper.wrap("");

        if (readOnly) {
            this.timer = null;
        }
        else {
            this.timer = new DelayTimer("Storage Writer", false, new DelayTimer.Callback() {
                @Override
                public
                void execute() {
                    Map<ByteArrayWrapper, Object> actions = DiskStorage.this.actionMap;

                    ReentrantLock actionLock2 = DiskStorage.this.actionLock;

                    try {
                        actionLock2.lock();

                        // do a fast swap on the actionMap.
                        DiskStorage.this.actionMap = new ConcurrentHashMap<ByteArrayWrapper, Object>();
                    } finally {
                        actionLock2.unlock();
                    }

                    DiskStorage.this.storage.doActionThings(actions);
                }
            });
        }

        this.isOpen.set(true);
    }



    /**
     * Returns the number of objects in the database.
     * <p/>
     * SLOW because this must save all data to disk first!
     */
    @Override
    public final
    int size() {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        // flush actions
        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
        }

        return this.storage.size();
    }

    /**
     * Checks if there is a object corresponding to the given key.
     */
    @Override
    public final
    boolean contains(String key) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        final ByteArrayWrapper wrap = ByteArrayWrapper.wrap(key);
        // check if our pending actions has it, or if our storage index has it
        return this.actionMap.containsKey(wrap) || this.storage.contains(wrap);
    }

    /**
     * Reads a object using the default (blank) key, and casts it to the expected class
     */
    @Override
    public final
    <T> T get() {
        return get0(this.defaultKey);
    }

    /**
     * Reads a object using the specific key, and casts it to the expected class
     */
    @Override
    public final
    <T> T get(String key) {
        return get0(ByteArrayWrapper.wrap(key));
    }

    /**
     * Reads a object using the specific key, and casts it to the expected class
     */
    @Override
    public final
    <T> T get(byte[] key) {
        return get0(ByteArrayWrapper.wrap(key));
    }

    /**
     * Reads a object using the specific key, and casts it to the expected class
     */
    @Override
    public final
    <T> T get(ByteArrayWrapper key) {
        return get0(key);
    }

    /**
     * Uses the DEFAULT key ("") to return saved data. Also saves the data.
     * <p/>
     * This will check to see if there is an associated key for that data, if not - it will use data as the default
     *
     * @param data The data that will hold the copy of the data from disk
     */
    @Override
    public
    <T> T getAndPut(T data) throws IOException {
        return getAndPut(this.defaultKey, data);
    }

    /**
     * Returns the saved data for the specified key. Also saves the data.
     *
     * @param data If there is no object in the DB with the specified key, this value will be the default (and will be saved to the db)
     */
    @Override
    public
    <T> T getAndPut(String key, T data) throws IOException {
        ByteArrayWrapper wrap = ByteArrayWrapper.wrap(key);

        return getAndPut(wrap, data);
    }

    /**
     * Returns the saved data for the specified key. Also saves the data.
     *
     * @param data If there is no object in the DB with the specified key, this value will be the default (and will be saved to the db)
     */
    @Override
    public
    <T> T getAndPut(byte[] key, T data) throws IOException {
        return getAndPut(ByteArrayWrapper.wrap(key), data);
    }

    /**
     * Returns the saved data for the specified key. Also saves the data.
     *
     * @param data If there is no object in the DB with the specified key, this value will be the default (and will be saved to the db)
     */
    @Override
    @SuppressWarnings("unchecked")
    public
    <T> T getAndPut(ByteArrayWrapper key, T data) throws IOException {
        Object source = get0(key);

        if (source == null) {
            // returned was null, so we should take value as the default
            putAndSave(key, data);
            return data;
        }
        else {
            final Class<?> expectedClass = data.getClass();
            final Class<?> savedCLass = source.getClass();

            if (!expectedClass.isAssignableFrom(savedCLass)) {
                throw new IOException("Saved value type '" + source.getClass() + "' is different that expected value");
            }
        }

        return (T) source;
    }

    /**
     * Reads a object from pending or from storage
     */
    private
    <T> T get0(ByteArrayWrapper key) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        // if the object in is pending, we get it from there
        try {
            this.actionLock.lock();

            Object object = this.actionMap.get(key);

            if (object != null) {
                @SuppressWarnings("unchecked")
                T returnObject = (T) object;
                return returnObject;
            }
        } finally {
            this.actionLock.unlock();
        }

        // not found, so we have to go find it on disk
        return this.storage.get(key);
    }

    /**
     * Saves the given data to storage with the associated key.
     * <p/>
     * Also will update existing data. If the new contents do not fit in the original space, then the update is handled by
     * deleting the old data and adding the new.
     */
    @Override
    public final
    void put(String key, Object object) {
        put(ByteArrayWrapper.wrap(key), object);
    }

    /**
     * Saves the given data to storage with the associated key.
     * <p/>
     * Also will update existing data. If the new contents do not fit in the original space, then the update is handled by
     * deleting the old data and adding the new.
     */
    @Override
    public final
    void put(byte[] key, Object object) {
        put(ByteArrayWrapper.wrap(key), object);
    }

    /**
     * Saves the given data to storage with the associated key.
     * <p/>
     * Also will update existing data. If the new contents do not fit in the original space, then the update is handled by
     * deleting the old data and adding the new.
     */
    @Override
    public final
    void put(ByteArrayWrapper key, Object object) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        if (timer != null) {
            action(key, object);

            // timer action runs on TIMER thread, not this thread
            this.timer.delay(this.milliSeconds);
        }
    }

    /**
     * Adds the given object to the storage using a default (blank) key, OR -- if it has been registered, using it's registered key
     * <p/>
     * Also will update existing data. If the new contents do not fit in the original space, then the update is handled by
     * deleting the old data and adding the new.
     */
    @Override
    public final
    void put(Object object) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        if (timer != null) {
            action(this.defaultKey, object);

            // timer action runs on TIMER thread, not this thread
            this.timer.delay(this.milliSeconds);
        }
    }

    /**
     * Deletes an object from storage.
     *
     * @return true if the delete was successful. False if there were problems deleting the data.
     */
    @Override
    public final
    boolean delete(String key) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        ByteArrayWrapper wrap = ByteArrayWrapper.wrap(key);

        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
            return this.storage.delete(wrap);
        }
        else {
            return false;
        }
    }

    /**
     * Deletes an object from storage.
     *
     * @return true if the delete was successful. False if there were problems deleting the data.
     */
    @Override
    public final
    boolean delete(ByteArrayWrapper key) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
            return this.storage.delete(key);
        }
        else {
            return false;
        }
    }

    /**
     * Closes the database and file.
     */
    void close() {
        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
        }

        // have to "close" it after we run the timer!
        this.isOpen.set(false);
        this.storage.close();
    }

    /**
     * @return the file that backs this storage
     */
    @Override
    public final
    File getFile() {
        return this.storage.getFile();
    }

    /**
     * Gets the backing file size.
     *
     * @return -1 if there was an error
     */
    @Override
    public final
    long getFileSize() {
        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
        }

        return this.storage.getFileSize();
    }

    /**
     * @return true if there are objects queued to be written?
     */
    @Override
    public final
    boolean hasWriteWaiting() {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        if (timer != null) {
            return this.timer.isWaiting();
        }
        else {
            return false;
        }
    }

    /**
     * @return the delay in milliseconds this will wait after the last action to flush the data to the disk
     */
    @Override
    public final
    long getSaveDelay() {
        return this.milliSeconds;
    }

    /**
     * @param milliSeconds milliseconds to wait
     */
    @Override
    public final
    void setSaveDelay(long milliSeconds) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        this.milliSeconds = milliSeconds;
    }

    /**
     * @return the version of data stored in the database
     */
    @Override
    public final
    int getVersion() {
        return this.storage.getVersion();
    }

    /**
     * Sets the version of data stored in the database
     */
    @Override
    public final
    void setVersion(int version) {
        this.storage.setVersion(version);
    }

    private
    void action(ByteArrayWrapper key, Object object) {
        try {
            this.actionLock.lock();

            if (object != null) {
                // push action to map
                this.actionMap.put(key, object);
            } else {
                this.actionMap.remove(key);
            }


        } finally {
            this.actionLock.unlock();
        }
    }

    void increaseReference() {
        this.references.incrementAndGet();
    }

    /**
     * return true when this is the last reference
     */
    boolean decrementReference() {
        return this.references.decrementAndGet() <= 0;
    }

    @Override
    protected
    Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /**
     * Save the storage to disk, immediately.
     * <p/>
     * This will save the ALL of the pending save actions to the file
     */
    @Override
    public final
    void save() {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            this.timer.delay(0L);
        }
    }

    /**
     * Save the storage to disk, immediately.
     * <p/>
     * This will save the ALL of the pending save actions to the file
     */
    @Override
    public
    void putAndSave(final String key, final Object object) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        // timer action runs on THIS thread, not timer thread
        if (timer != null) {
            action(ByteArrayWrapper.wrap(key), object);
            this.timer.delay(0L);
        }
    }

    /**
     * Save the storage to disk, immediately.
     * <p/>
     * This will save the ALL of the pending save actions to the file
     */
    @Override
    public
    void putAndSave(final byte[] key, final Object object) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        if (timer != null) {
            action(ByteArrayWrapper.wrap(key), object);

            // timer action runs on THIS thread, not timer thread
            this.timer.delay(0L);
        }

    }

    /**
     * Save the storage to disk, immediately.
     * <p/>
     * This will save the ALL of the pending save actions to the file
     */
    @Override
    public
    void putAndSave(final ByteArrayWrapper key, final Object object) {
        if (!this.isOpen.get()) {
            throw new RuntimeException("Unable to act on closed storage");
        }

        if (timer != null) {
            action(key, object);

            // timer action runs on THIS thread, not timer thread
            this.timer.delay(0L);
        }
    }
}
