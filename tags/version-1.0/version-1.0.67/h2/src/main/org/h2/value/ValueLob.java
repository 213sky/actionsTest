/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.value;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.message.Message;
import org.h2.store.DataHandler;
import org.h2.store.FileStore;
import org.h2.store.FileStoreInputStream;
import org.h2.store.FileStoreOutputStream;
import org.h2.store.fs.FileSystem;
import org.h2.util.ByteUtils;
import org.h2.util.FileUtils;
import org.h2.util.IOUtils;
import org.h2.util.MathUtils;
import org.h2.util.RandomUtils;
import org.h2.util.StringUtils;

/**
 * Implementation of the BLOB and CLOB data types.
 */
public class ValueLob extends Value {
    // TODO lob: concatenate function for blob and clob (to create a large blob from pieces)
    // and a getpart function (to get it in pieces) and make sure a file is created!

    public static final int TABLE_ID_SESSION = -1;

    private final int type;
    private long precision;
    private DataHandler handler;
    private int tableId;
    private int objectId;
    private String fileName;
    private boolean linked;
    private byte[] small;
    private int hash;
    private boolean compression;
    private FileStore tempFile;

    private ValueLob(int type, DataHandler handler, String fileName, int tableId, int objectId, boolean linked,
            long precision, boolean compression) {
        this.type = type;
        this.handler = handler;
        this.fileName = fileName;
        this.tableId = tableId;
        this.objectId = objectId;
        this.linked = linked;
        this.precision = precision;
        this.compression = compression;
    }

    private static ValueLob copy(ValueLob lob) {
        ValueLob copy = new ValueLob(lob.type, lob.handler, lob.fileName, lob.tableId, lob.objectId, lob.linked, lob.precision, lob.compression);
        copy.small = lob.small;
        copy.hash = lob.hash;
        return copy;
    }

    private ValueLob(int type, byte[] small) throws SQLException {
        this.type = type;
        this.small = small;
        if (small != null) {
            if (type == Value.BLOB) {
                this.precision = small.length;
            } else {
                this.precision = getString().length();
            }
        }
    }

    public static ValueLob createSmallLob(int type, byte[] small) throws SQLException {
        return new ValueLob(type, small);
    }

    private static String getFileName(DataHandler handler, int tableId, int objectId) {
        if (SysProperties.CHECK && tableId == 0 && objectId == 0) {
            throw Message.getInternalError("0 LOB");
        }
        if (SysProperties.LOB_FILES_IN_DIRECTORIES) {
            String table = tableId < 0 ? ".temp" : ".t" + tableId;
            return getFileNamePrefix(handler.getDatabasePath(), objectId) + table + Constants.SUFFIX_LOB_FILE;
        } else {
            return handler.getDatabasePath() + "." + tableId + "." + objectId + Constants.SUFFIX_LOB_FILE;
        }
    }

    public static ValueLob open(int type, DataHandler handler, int tableId, int objectId, long precision, boolean compression) {
        String fileName = getFileName(handler, tableId, objectId);
        return new ValueLob(type, handler, fileName, tableId, objectId, true, precision, compression);
    }

    public static ValueLob createClob(Reader in, long length, DataHandler handler) throws SQLException {
        try {
            boolean compress = handler.getLobCompressionAlgorithm(Value.CLOB) != null;
            long remaining = Long.MAX_VALUE;
            if (length >= 0 && length < remaining) {
                remaining = length;
            }
            int len = getBufferSize(handler, compress, remaining);
            char[] buff = new char[len];
            len = IOUtils.readFully(in, buff, len);
            len = len < 0 ? 0 : len;
            if (len <= handler.getMaxLengthInplaceLob()) {
                byte[] small = StringUtils.utf8Encode(new String(buff, 0, len));
                return ValueLob.createSmallLob(Value.CLOB, small);
            }
            ValueLob lob = new ValueLob(Value.CLOB, null);
            lob.createFromReader(buff, len, in, remaining, handler);
            return lob;
        } catch (IOException e) {
            throw Message.convertIOException(e, null);
        }
    }

    private static int getBufferSize(DataHandler handler, boolean compress, long remaining) {
        if (remaining < 0 || remaining > Integer.MAX_VALUE) {
            remaining = Integer.MAX_VALUE;
        }
        long inplace = handler.getMaxLengthInplaceLob();
        if (inplace >= Integer.MAX_VALUE) {
           inplace = remaining;
        }
        long m = compress ? Constants.IO_BUFFER_SIZE_COMPRESS : Constants.IO_BUFFER_SIZE;
        if (m < remaining && m <= inplace) {
            m = Math.min(remaining, inplace + 1);
            // the buffer size must be bigger than the inplace lob, otherwise we can't
            // know if it must be stored in-place or not
            m = MathUtils.roundUpLong(m, Constants.IO_BUFFER_SIZE);
        }
        m = Math.min(remaining, m);
        m = MathUtils.convertLongToInt(m);
        if (m < 0) {
            m = Integer.MAX_VALUE;
        }
        return (int) m;
    }

    private void createFromReader(char[] buff, int len, Reader in, long remaining, DataHandler handler) throws SQLException {
        try {
            FileStoreOutputStream out = initLarge(handler);
            boolean compress = handler.getLobCompressionAlgorithm(Value.CLOB) != null;
            try {
                while (true) {
                    precision += len;
                    byte[] b = StringUtils.utf8Encode(new String(buff, 0, len));
                    out.write(b, 0, b.length);
                    remaining -= len;
                    if (remaining <= 0) {
                        break;
                    }
                    len = getBufferSize(handler, compress, remaining);
                    len = IOUtils.readFully(in, buff, len);
                    if (len <= 0) {
                        break;
                    }
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw Message.convertIOException(e, null);
        }
    }

    private static String getFileNamePrefix(String path, int objectId) {
        String name;
        int f = objectId % SysProperties.LOB_FILES_PER_DIRECTORY;
        if (f > 0) {
            name = File.separator + objectId;
        } else {
            name = "";
        }
        objectId /= SysProperties.LOB_FILES_PER_DIRECTORY;
        while (objectId > 0) {
            f = objectId % SysProperties.LOB_FILES_PER_DIRECTORY;
            name = File.separator + f + Constants.SUFFIX_LOBS_DIRECTORY + name;
            objectId /= SysProperties.LOB_FILES_PER_DIRECTORY;
        }
        name = path + Constants.SUFFIX_LOBS_DIRECTORY + name;
        return name;
    }

    private int getNewObjectId(DataHandler handler) throws SQLException {
        String path = handler.getDatabasePath();
        int objectId = 0;
        while (true) {
            String dir = getFileNamePrefix(path, objectId);
            String[] list = FileUtils.listFiles(dir);
            int fileCount = 0;
            boolean[] used = new boolean[SysProperties.LOB_FILES_PER_DIRECTORY];
            for (int i = 0; i < list.length; i++) {
                String name = list[i];
                if (name.endsWith(Constants.SUFFIX_DB_FILE)) {
                    name = name.substring(name.lastIndexOf(File.separatorChar) + 1);
                    String n = name.substring(0, name.indexOf('.'));
                    int id;
                    try {
                        id = Integer.parseInt(n);
                    } catch (NumberFormatException e) {
                        id = -1;
                    }
                    if (id > 0) {
                        fileCount++;
                        used[id % SysProperties.LOB_FILES_PER_DIRECTORY] = true;
                    }
                }
            }
            int fileId = -1;
            if (fileCount < SysProperties.LOB_FILES_PER_DIRECTORY) {
                for (int i = 1; i < SysProperties.LOB_FILES_PER_DIRECTORY; i++) {
                    if (!used[i]) {
                        fileId = i;
                        break;
                    }
                }
            }
            if (fileId > 0) {
                objectId += fileId;
                break;
            } else {
                if (objectId > Integer.MAX_VALUE / SysProperties.LOB_FILES_PER_DIRECTORY) {
                    // this directory path is full: start from zero
                    // (this can happen only theoretically, for example if the random number generator is broken)
                    objectId = 0;
                } else {
                    // start with 1 (otherwise we don't know the number of directories)
                    int dirId = RandomUtils.nextInt(SysProperties.LOB_FILES_PER_DIRECTORY - 1) + 1;
                    objectId = objectId * SysProperties.LOB_FILES_PER_DIRECTORY;
                    objectId += dirId * SysProperties.LOB_FILES_PER_DIRECTORY;
                }
            }
        }
        return objectId;
    }

    public static ValueLob createBlob(InputStream in, long length, DataHandler handler) throws SQLException {
        try {
            long remaining = Long.MAX_VALUE;
            boolean compress = handler.getLobCompressionAlgorithm(Value.BLOB) != null;
            if (length >= 0 && length < remaining) {
                remaining = length;
            }
            int len = getBufferSize(handler, compress, remaining);
            byte[] buff = new byte[len];
            len = IOUtils.readFully(in, buff, 0, len);
            if (len <= handler.getMaxLengthInplaceLob()) {
                byte[] small = new byte[len];
                System.arraycopy(buff, 0, small, 0, len);
                return ValueLob.createSmallLob(Value.BLOB, small);
            }
            ValueLob lob = new ValueLob(Value.BLOB, null);
            lob.createFromStream(buff, len, in, remaining, handler);
            return lob;
        } catch (IOException e) {
            throw Message.convertIOException(e, null);
        }
    }

    private FileStoreOutputStream initLarge(DataHandler handler) throws IOException, SQLException {
        this.handler = handler;
        this.tableId = 0;
        this.linked = false;
        this.precision = 0;
        this.small = null;
        this.hash = 0;
        String compressionAlgorithm = handler.getLobCompressionAlgorithm(type);
        this.compression = compressionAlgorithm != null;
        synchronized (handler) {
            if (SysProperties.LOB_FILES_IN_DIRECTORIES) {
                objectId = getNewObjectId(handler);
                fileName = getFileNamePrefix(handler.getDatabasePath(), objectId) + ".temp.db";
            } else {
                objectId = handler.allocateObjectId(false, true);
                fileName = handler.createTempFile();
            }
            tempFile = handler.openFile(fileName, "rw", false);
            tempFile.autoDelete();
        }
        FileStoreOutputStream out = new FileStoreOutputStream(tempFile, handler, compressionAlgorithm);
        return out;
    }

    private void createFromStream(byte[] buff, int len, InputStream in, long remaining, DataHandler handler)
            throws SQLException {
        try {
            FileStoreOutputStream out = initLarge(handler);
            boolean compress = handler.getLobCompressionAlgorithm(Value.BLOB) != null;
            try {
                while (true) {
                    precision += len;
                    out.write(buff, 0, len);
                    remaining -= len;
                    if (remaining <= 0) {
                        break;
                    }
                    len = getBufferSize(handler, compress, remaining);
                    len = IOUtils.readFully(in, buff, 0, len);
                    if (len <= 0) {
                        break;
                    }
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw Message.convertIOException(e, null);
        }
    }

    public Value convertTo(int t) throws SQLException {
        if (t == type) {
            return this;
        } else if (t == Value.CLOB) {
            ValueLob copy = ValueLob.createClob(getReader(), -1, handler);
            return copy;
        } else if (t == Value.BLOB) {
            ValueLob copy = ValueLob.createBlob(getInputStream(), -1, handler);
            return copy;
        }
        return super.convertTo(t);
    }

    public boolean isLinked() {
        return linked;
    }

    public String getFileName() {
        return fileName;
    }

    public void close() throws SQLException {
        if (fileName != null) {
            if (tempFile != null) {
                tempFile.stopAutoDelete();
            }
            deleteFile(handler, fileName);
        }
    }

    public void unlink() throws SQLException {
        if (linked && fileName != null) {
            String temp;
            // synchronize on the database, to avoid concurrent temp file
            // creation / deletion / backup
            synchronized (handler) {
                if (SysProperties.LOB_FILES_IN_DIRECTORIES) {
                    temp = getFileName(handler, -1, objectId);
                } else {
                    // just to get a filename - an empty file will be created
                    temp = handler.createTempFile();
                }
                deleteFile(handler, temp);
                renameFile(handler, fileName, temp);
                tempFile = FileStore.open(handler, temp, "rw", null);
                tempFile.autoDelete();
                tempFile.closeSilently();
                fileName = temp;
                linked = false;
            }
        }
    }

    public Value link(DataHandler handler, int tabId) throws SQLException {
        if (fileName == null) {
            this.tableId = tabId;
            return this;
        }
        if (linked) {
            ValueLob copy = ValueLob.copy(this);
            if (SysProperties.LOB_FILES_IN_DIRECTORIES) {
                copy.objectId = getNewObjectId(handler);
            } else {
                copy.objectId = handler.allocateObjectId(false, true);
            }
            copy.tableId = tabId;
            String live = getFileName(handler, copy.tableId, copy.objectId);
            copyFile(handler, fileName, live);
            copy.fileName = live;
            copy.linked = true;
            return copy;
        }
        if (!linked) {
            this.tableId = tabId;
            String live = getFileName(handler, tableId, objectId);
            tempFile.stopAutoDelete();
            tempFile = null;
            renameFile(handler, fileName, live);
            fileName = live;
            linked = true;
        }
        return this;
    }

    public int getTableId() {
        return tableId;
    }

    public int getObjectId() {
        return objectId;
    }

    public int getType() {
        return type;
    }

    public long getPrecision() {
        return precision;
    }

    public String getString() throws SQLException {
        int len = precision > Integer.MAX_VALUE || precision == 0 ? Integer.MAX_VALUE : (int) precision;
        try {
            if (type == Value.CLOB) {
                if (small != null) {
                    return StringUtils.utf8Decode(small);
                }
                return IOUtils.readStringAndClose(getReader(), len);
            } else {
                byte[] buff;
                if (small != null) {
                    buff = small;
                } else {
                    buff = IOUtils.readBytesAndClose(getInputStream(), len);
                }
                return ByteUtils.convertBytesToString(buff);
            }
        } catch (IOException e) {
            throw Message.convertIOException(e, fileName);
        }
    }

    public byte[] getBytes() throws SQLException {
        byte[] data = getBytesNoCopy();
        return ByteUtils.cloneByteArray(data);
    }

    public byte[] getBytesNoCopy() throws SQLException {
        if (small != null) {
            return small;
        }
        try {
            return IOUtils.readBytesAndClose(getInputStream(), Integer.MAX_VALUE);
        } catch (IOException e) {
            throw Message.convertIOException(e, fileName);
        }
    }

    public int hashCode() {
        if (hash == 0) {
            if (precision > 4096) {
                // TODO: should calculate the hash code when saving, and store
                // it in the data file
                return (int) (precision ^ (precision >> 32));
            }
            try {
                hash = ByteUtils.getByteArrayHash(getBytes());
            } catch (SQLException e) {
                // TODO hash code for lob: should not ignore exception
            }
        }
        return hash;
    }

    protected int compareSecure(Value v, CompareMode mode) throws SQLException {
        if (type == Value.CLOB) {
            int c = getString().compareTo(v.getString());
            return c == 0 ? 0 : (c < 0 ? -1 : 1);
        } else {
            byte[] v2 = v.getBytesNoCopy();
            return ByteUtils.compareNotNull(getBytes(), v2);
        }
    }

    public Object getObject() throws SQLException {
        if (type == Value.CLOB) {
            return getReader();
        } else {
            return getInputStream();
        }
    }

    public Reader getReader() throws SQLException {
        return IOUtils.getReader(getInputStream());
    }

    public InputStream getInputStream() throws SQLException {
        if (fileName == null) {
            return new ByteArrayInputStream(small);
        }
        FileStore store = handler.openFile(fileName, "r", true);
        boolean alwaysClose = SysProperties.lobCloseBetweenReads;
        return new BufferedInputStream(new FileStoreInputStream(store, handler, compression, alwaysClose),
                Constants.IO_BUFFER_SIZE);
    }

    public void set(PreparedStatement prep, int parameterIndex) throws SQLException {
        long p = getPrecision();
        // TODO test if setBinaryStream with -1 works for other databases a well
        if (p > Integer.MAX_VALUE || p <= 0) {
            p = -1;
        }
        if (type == Value.BLOB) {
            prep.setBinaryStream(parameterIndex, getInputStream(), (int) p);
        } else {
            prep.setCharacterStream(parameterIndex, getReader(), (int) p);
        }
    }

    public String getSQL() {
        try {
            String s;
            if (type == Value.CLOB) {
                s = getString();
                return StringUtils.quoteStringSQL(s);
            } else {
                byte[] buff = getBytes();
                s = ByteUtils.convertBytesToString(buff);
                return "X'" + s + "'";
            }
        } catch (SQLException e) {
            throw Message.convertToInternal(e);
        }
    }

    public String toString() {
        if (small == null) {
            return getClass().getName() + " file: " + fileName + " type: " + type + " precision: " + precision;
        } else {
            return getSQL();
        }
    }

    public byte[] getSmall() {
        return small;
    }

    public int getDisplaySize() {
        // TODO display size of lob?
        return MathUtils.convertLongToInt(getPrecision());
    }

    protected boolean isEqual(Value v) {
        try {
            return compareSecure(v, null) == 0;
        } catch (SQLException e) {
            // TODO exceptions: improve concept, maybe remove throws
            // SQLException almost everywhere
            throw Message.getInternalError("compare", e);
        }
    }

    public void convertToFileIfRequired(DataHandler handler) throws SQLException {
        if (Constants.AUTO_CONVERT_LOB_TO_FILES && small != null && small.length > handler.getMaxLengthInplaceLob()) {
            boolean compress = handler.getLobCompressionAlgorithm(type) != null;
            int len = getBufferSize(handler, compress, Long.MAX_VALUE);
            int tabId = tableId;
            if (type == Value.BLOB) {
                createFromStream(new byte[len], 0, getInputStream(), Long.MAX_VALUE, handler);
            } else {
                createFromReader(new char[len], 0, getReader(), Long.MAX_VALUE, handler);
            }
            Value v2 = link(handler, tabId);
            if (SysProperties.CHECK && v2 != this) {
                throw Message.getInternalError();
            }
        }
    }

    public static void removeAllForTable(DataHandler handler, int tableId) throws SQLException {
        if (SysProperties.LOB_FILES_IN_DIRECTORIES) {
            String dir = getFileNamePrefix(handler.getDatabasePath(), 0);
            removeAllForTable(handler, dir, tableId);
        } else {
            String prefix = handler.getDatabasePath();
            String dir = FileUtils.getParent(prefix);
            String[] list = FileUtils.listFiles(dir);
            for (int i = 0; i < list.length; i++) {
                String name = list[i];
                if (name.startsWith(prefix + "." + tableId + ".") && name.endsWith(".lob.db")) {
                    deleteFile(handler, name);
                }
            }
        }
    }

    private static void removeAllForTable(DataHandler handler, String dir, int tableId) throws SQLException {
        String[] list = FileUtils.listFiles(dir);
        for (int i = 0; i < list.length; i++) {
            if (FileUtils.isDirectory(list[i])) {
                removeAllForTable(handler, list[i], tableId);
            } else {
                String name = list[i];
                if (name.endsWith(".t" + tableId + ".lob.db")) {
                    deleteFile(handler, name);
                }
            }
        }
    }

    public boolean useCompression() {
        return compression;
    }

    public boolean isFileBased() {
        return fileName != null;
    }

    private static synchronized void deleteFile(DataHandler handler, String fileName) throws SQLException {
        // synchronize on the database, to avoid concurrent temp file creation /
        // deletion / backup
        synchronized (handler.getLobSyncObject()) {
            FileUtils.delete(fileName);
        }
    }

    private static synchronized void renameFile(DataHandler handler, String oldName, String newName)
            throws SQLException {
        synchronized (handler.getLobSyncObject()) {
            FileUtils.rename(oldName, newName);
        }
    }

    private void copyFile(DataHandler handler, String fileName, String live) throws SQLException {
        synchronized (handler.getLobSyncObject()) {
            FileSystem.getInstance(fileName).copy(fileName, live);
        }
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getMemory() {
        if (small != null) {
            return small.length + 32;
        }
        return 128;
    }

}