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

import java.io.File;

/**
 *
 */
@SuppressWarnings("unused")
public
interface Storage {
    /**
     * Returns the number of objects in the database.
     */
    int size();

    /**
     * Checks if there is a object corresponding to the given key.
     */
    boolean contains(StorageKey key);

    /**
     * Reads a object using the specific key, and casts it to the expected class
     */
    <T> T get(StorageKey key);

    /**
     * Returns the saved data for the specified key.
     *
     * @param key The key used to check if data already exists.
     * @param data This is the default value, and if there is no value with the key in the DB this default value will be saved.
     */
    <T> T get(StorageKey key, T data);

    /**
     * Saves the given data to storage with the associated key.
     * <p/>
     * Also will update existing data. If the new contents do not fit in the original space, then the update is handled by
     * deleting the old data and adding the new.
     */
    void put(StorageKey key, Object data);

    /**
     * Deletes an object from storage.
     *
     * @return true if the delete was successful. False if there were problems deleting the data.
     */
    boolean delete(StorageKey key);

    /**
     * @return the file that backs this storage
     */
    File getFile();

    /**
     * Gets the backing file size.
     *
     * @return -1 if there was an error
     */
    long getFileSize();

    /**
     * @return true if there are objects queued to be written?
     */
    boolean hasWriteWaiting();

    /**
     * @return the delay in milliseconds this will wait after the last action to flush the data to the disk
     */
    long getSaveDelay();

    /**
     * @return the version of data stored in the database
     */
    int getVersion();

    /**
     * Sets the version of data stored in the database
     */
    void setVersion(int version);

    /**
     * Save the storage to disk, immediately.
     * <p/>
     * This will save the ALL of the pending save actions to the file
     */
    void save();

    /**
     * Closes this storage system
     */
    void close();
}
