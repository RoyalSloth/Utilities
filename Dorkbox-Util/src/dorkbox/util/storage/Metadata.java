package dorkbox.util.storage;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.util.bytes.ByteArrayWrapper;

public class Metadata {
    // The length of a key in the index.
    // SHA256 is 32 bytes long.
    private static final int  KEY_SIZE = 32;

    // Number of bytes in the record header.
    private static final int POINTER_INFO_SIZE = 16;

    // The total length of one index entry - the key length plus the record header length.
    static final int  INDEX_ENTRY_LENGTH = KEY_SIZE + POINTER_INFO_SIZE;


    /**
     * This is the key to the index
     */
    final ByteArrayWrapper key;

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
    volatile int  dataCount;

    /**
     * Number of bytes of data that this record can hold (4 bytes).
     */
    volatile int  dataCapacity;


    /**
     * The object that has been registered to this key. This is for automatic saving of data (if it's changed)
     */
    volatile WeakReference<Object> objectReferenceCache;



    /**
     * Returns a file pointer in the index pointing to the first byte in the KEY located at the given index position.
     */
    static final long getMetaDataPointer(int position) {
        return StorageBase.FILE_HEADERS_REGION_LENGTH + INDEX_ENTRY_LENGTH * position;
    }

    /**
     * Returns a file pointer in the index pointing to the first byte in the RECORD pointer located at the given index
     * position.
     */
    static final long getDataPointer(int position) {
        long l = Metadata.getMetaDataPointer(position) + KEY_SIZE;
        return l;
    }


    private Metadata(ByteArrayWrapper key) {
        this.key = key;
    }

    /** we don't know how much data there is until AFTER we write the data */
    Metadata(ByteArrayWrapper key, int recordIndex, long dataPointer) {
        this(key, recordIndex, dataPointer, 0);
    }

    Metadata(ByteArrayWrapper key, int recordIndex, long dataPointer, int dataCapacity) {
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

    int getFreeSpace() {
        return this.dataCapacity - this.dataCount;
    }

    /**
     * Reads the ith HEADER (key + metadata) from the index.
     */
    static Metadata readHeader(RandomAccessFile file, int position) throws IOException {
        byte[] buf = new byte[KEY_SIZE];
        long origHeaderKeyPointer = Metadata.getMetaDataPointer(position);
        file.seek(origHeaderKeyPointer);
        file.readFully(buf);

        Metadata r = new Metadata(ByteArrayWrapper.wrap(buf));
        r.indexPosition = position;

        long recordHeaderPointer = Metadata.getDataPointer(position);
        file.seek(recordHeaderPointer);
        r.dataPointer = file.readLong();
        r.dataCapacity = file.readInt();
        r.dataCount = file.readInt();

        if (r.dataPointer == 0L || r.dataCapacity == 0L || r.dataCount == 0L) {
            return null;
        }

        return r;
    }


    void write(RandomAccessFile file) throws IOException {
        writeMetaDataInfo(file);
        writeDataInfo(file);
    }

    void writeMetaDataInfo(RandomAccessFile file) throws IOException {
        long recordKeyPointer = Metadata.getMetaDataPointer(this.indexPosition);

        file.seek(recordKeyPointer);
        file.write(this.key.getBytes());
    }

    void writeDataInfo(RandomAccessFile file) throws IOException {
        long recordHeaderPointer = getDataPointer(this.indexPosition);

        file.seek(recordHeaderPointer);
        file.writeLong(this.dataPointer);
        file.writeInt(this.dataCapacity);
        file.writeInt(this.dataCount);
    }

    /**
     * Move a record to the new INDEX
     */
    void move(RandomAccessFile file, int newIndex) throws IOException {
        byte[] buf = new byte[KEY_SIZE];

        long origHeaderKeyPointer = Metadata.getMetaDataPointer(this.indexPosition);
        file.seek(origHeaderKeyPointer);
        file.readFully(buf);

        long newHeaderKeyPointer = Metadata.getMetaDataPointer(newIndex);
        file.seek(newHeaderKeyPointer);
        file.write(buf);

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

        // update the file size
        file.setLength(position + this.dataCount);

//        System.err.print("moving data: " +  this.indexPosition + " @ " + this.dataPointer + "-" + (this.dataPointer+data.length) + " -- ");
//        Sys.printArray(data, data.length, false, 0);
        // save the data
        file.seek(position);
        file.write(data);

        // update header pointer info
        writeDataInfo(file);
    }


    /**
     * Reads the record data for the given record header.
     */
    byte[] readDataRaw(RandomAccessFile file) throws IOException {

        byte[] buf = new byte[this.dataCount];
//        System.err.print("Reading data: " + this.indexPosition + " @ " + this.dataPointer + "-" + (this.dataPointer+this.dataCount) + " -- ");

        file.seek(this.dataPointer);
        file.readFully(buf);
//        Sys.printArray(buf, buf.length, false, 0);

        return buf;
    }

   /**
    * Reads the record data for the given record header.
    */
    @SuppressWarnings("unchecked")
    <T> T readData(Kryo kryo, InflaterInputStream inputStream) throws IOException {
        Input input = new Input(inputStream, 1024); // read 1024 at a time
        Object readObject = kryo.readClassAndObject(input);
        return (T) readObject;
    }


    ByteArrayOutputStream getDataStream(Kryo kryo, Object data, Deflater deflater) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        OutputStream outputStream = new DeflaterOutputStream(byteArrayOutputStream, deflater);
        Output output = new Output(outputStream, 1024); // write 1024 at a time
        kryo.writeClassAndObject(output, data);
        output.flush();

        outputStream.flush();
        outputStream.close();

        return byteArrayOutputStream;
    }

    /**
     * Writes data to the end of the file (which is where the datapointer is at)
     */
    void writeDataToEndOfFile(Kryo kryo, Object data, DeflaterOutputStream outputStream) throws IOException {
        Output output = new Output(outputStream, 1024); // write 1024 at a time
        kryo.writeClassAndObject(output, data);
        output.flush();

        outputStream.flush();
        outputStream.finish(); // have to make sure that the outputstream finishes compressing data
    }

    void writeData(ByteArrayOutputStream byteArrayOutputStream, RandomAccessFile file) throws IOException {
        this.dataCount = byteArrayOutputStream.size();
        FileOutputStream out = new FileOutputStream(file.getFD());
        file.seek(this.dataPointer);
        byteArrayOutputStream.writeTo(out);
        out.flush();
    }

    @Override
    public String toString() {
        return "RecordHeader [dataPointer=" + this.dataPointer + ", dataCount=" + this.dataCount + ", dataCapacity="
                + this.dataCapacity + ", indexPosition=" + this.indexPosition + "]";
    }
}