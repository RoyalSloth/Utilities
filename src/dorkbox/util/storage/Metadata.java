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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.channels.FileLock;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.util.serialization.SerializationManager;

public
class Metadata {
    // The length of a key in the index.
    // SHA256 is 32 bytes long.
    private static final int KEY_SIZE = 32;

    // Number of bytes in the record header.
    private static final int POINTER_INFO_SIZE = 16;

    // The total length of one index entry - the key length plus the record header length.
    static final int INDEX_ENTRY_LENGTH = KEY_SIZE + POINTER_INFO_SIZE;


    /**
     * This is the key to the index
     */
    final StorageKey key;

    /**
     * Indicates this header's position in the file index.
     */
    volatile int indexPosition;

    /**
     * File pointer to the first byte of record data (8 bytes).
     */
    volatile long dataPointer;

    /**
     * Actual number of bytes of data held in this record (4 bytes).
     */
    volatile int dataCount;

    /**
     * Number of bytes of data that this record can hold (4 bytes).
     */
    volatile int dataCapacity;


    /**
     * The object that has been registered to this key. This is for automatic saving of data (if it's changed)
     */
    volatile WeakReference<Object> objectReferenceCache;



    /**
     * Returns a file pointer in the index pointing to the first byte in the KEY located at the given index position.
     */
    static
    long getMetaDataPointer(int position) {
        return StorageBase.FILE_HEADERS_REGION_LENGTH + ((long) INDEX_ENTRY_LENGTH) * position;
    }

    /**
     * Returns a file pointer in the index pointing to the first byte in the RECORD pointer located at the given index
     * position.
     */
    private static
    long getDataPointer(int position) {
        return Metadata.getMetaDataPointer(position) + KEY_SIZE;
    }


    private
    Metadata(StorageKey key) {
        this.key = key;
    }

    /**
     * we don't know how much data there is until AFTER we write the data
     */
    Metadata(StorageKey key, int recordIndex, long dataPointer) {
        this(key, recordIndex, dataPointer, 0);
    }

    private
    Metadata(StorageKey key, int recordIndex, long dataPointer, int dataCapacity) {
        if (key.getBytes().length > KEY_SIZE) {
            throw new IllegalArgumentException("Bad record key size: " + dataCapacity);
        }


        this.key = key;
        this.indexPosition = recordIndex;
        this.dataPointer = dataPointer;

        // we don't always know the size!
        this.dataCapacity = dataCapacity;
        this.dataCount = dataCapacity;
    }

    @SuppressWarnings("unused")
    int getFreeSpace() {
        return this.dataCapacity - this.dataCount;
    }

    /**
     * Reads the Nth HEADER (key + metadata) from the index.
     */
    static
    Metadata readHeader(RandomAccessFile file, int position) throws IOException {
        byte[] buf = new byte[KEY_SIZE];
        long origHeaderKeyPointer = Metadata.getMetaDataPointer(position);

        FileLock lock = file.getChannel()
                            .lock(origHeaderKeyPointer, INDEX_ENTRY_LENGTH, true);

        file.seek(origHeaderKeyPointer);
        file.readFully(buf);

        lock.release();

        Metadata r = new Metadata(new StorageKey(buf));
        r.indexPosition = position;

        long recordHeaderPointer = Metadata.getDataPointer(position);
        lock = file.getChannel()
                   .lock(origHeaderKeyPointer, KEY_SIZE, true);

        file.seek(recordHeaderPointer);
        r.dataPointer = file.readLong();
        r.dataCapacity = file.readInt();
        r.dataCount = file.readInt();

        lock.release();

        if (r.dataPointer == 0L || r.dataCapacity == 0L || r.dataCount == 0L) {
            return null;
        }

        return r;
    }

    void writeMetaDataInfo(RandomAccessFile file) throws IOException {
        long recordKeyPointer = Metadata.getMetaDataPointer(this.indexPosition);

        FileLock lock = file.getChannel()
                            .lock(recordKeyPointer, KEY_SIZE, false);
        file.seek(recordKeyPointer);
        file.write(this.key.getBytes());
        lock.release();
    }

    void writeDataInfo(RandomAccessFile file) throws IOException {
        long recordHeaderPointer = getDataPointer(this.indexPosition);

        FileLock lock = file.getChannel()
                            .lock(recordHeaderPointer, POINTER_INFO_SIZE, false);

        file.seek(recordHeaderPointer);
        file.writeLong(this.dataPointer);
        file.writeInt(this.dataCapacity);
        file.writeInt(this.dataCount);

        lock.release();
    }

    /**
     * Move a record to the new INDEX
     */
    void moveRecord(RandomAccessFile file, int newIndex) throws IOException {
        byte[] buf = new byte[KEY_SIZE];

        long origHeaderKeyPointer = Metadata.getMetaDataPointer(this.indexPosition);
        FileLock lock = file.getChannel()
                            .lock(origHeaderKeyPointer, INDEX_ENTRY_LENGTH, true);

        file.seek(origHeaderKeyPointer);
        file.readFully(buf);

        lock.release();

        long newHeaderKeyPointer = Metadata.getMetaDataPointer(newIndex);
        lock = file.getChannel()
                   .lock(newHeaderKeyPointer, INDEX_ENTRY_LENGTH, false);

        file.seek(newHeaderKeyPointer);
        file.write(buf);

        lock.release();

//        System.err.println("updating ptr: " +  this.indexPosition + " -> " + newIndex + " @ " + newHeaderKeyPointer + "-" + (newHeaderKeyPointer+INDEX_ENTRY_LENGTH));
        this.indexPosition = newIndex;

        writeDataInfo(file);
    }

    /**
     * Move a record DATA to the new position, and update record header info
     */
    void moveData(RandomAccessFile file, long position) throws IOException {
        // now we move it to the end of the file.
        // we ALSO trim the free space off.
        byte[] data = readDataRaw(file);

        this.dataPointer = position;
        this.dataCapacity = this.dataCount;

        FileLock lock = file.getChannel()
                            .lock(position, this.dataCount, false);

        // update the file size
        file.setLength(position + this.dataCount);

//        System.err.print("moving data: " +  this.indexPosition + " @ " + this.dataPointer + "-" + (this.dataPointer+data.length) + " -- ");
//        Sys.printArray(data, data.length, false, 0);
        // save the data
        file.seek(position);
        file.write(data);

        lock.release();

        // update header pointer info
        writeDataInfo(file);
    }


    /**
     * Reads the record data for the given record header.
     */
    private
    byte[] readDataRaw(RandomAccessFile file) throws IOException {

        byte[] buf = new byte[this.dataCount];
//        System.err.print("Reading data: " + this.indexPosition + " @ " + this.dataPointer + "-" + (this.dataPointer+this.dataCount) + " -- ");

        FileLock lock = file.getChannel()
                            .lock(this.dataPointer, this.dataCount, true);
        file.seek(this.dataPointer);
        file.readFully(buf);

        lock.release();
//        Sys.printArray(buf, buf.length, false, 0);

        return buf;
    }

    /**
     * Reads the record data for the given record header.
     */

    static
    <T> T readData(final SerializationManager serializationManager, final Input input) throws IOException {
        // this is to reset the internal buffer of 'input'
        input.setInputStream(input.getInputStream());

        @SuppressWarnings("unchecked")
        T readObject = (T) serializationManager.readFullClassAndObject(input);
        return readObject;
    }

    /**
     * Writes data to the end of the file (which is where the datapointer is at). This must be locked/released in calling methods!
     */
    static
    int writeData(final SerializationManager serializationManager,
                  final Object data,
                  final Output output) throws IOException {

        output.reset();

        serializationManager.writeFullClassAndObject(output, data);
        output.flush();

        return (int) output.total();
    }

    void writeDataRaw(ByteArrayOutputStream byteArrayOutputStream, RandomAccessFile file) throws IOException {
        this.dataCount = byteArrayOutputStream.size();

        FileLock lock = file.getChannel()
                            .lock(this.dataPointer, this.dataCount, false);

        FileOutputStream out = new FileOutputStream(file.getFD());
        file.seek(this.dataPointer);
        byteArrayOutputStream.writeTo(out);


        lock.release();
    }

    @Override
    public
    String toString() {
        return "RecordHeader [dataPointer=" + this.dataPointer + ", dataCount=" + this.dataCount + ", dataCapacity=" + this.dataCapacity +
               ", indexPosition=" + this.indexPosition + "]";
    }
}
