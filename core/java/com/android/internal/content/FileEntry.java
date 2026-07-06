package com.android.internal.content;

public class FileEntry {

    public final String path;
    public final String name;
    public final String mime;
    public final String docId;

    public final int flags;
    public final long size;
    public final long mtime;
    public final boolean writable;

    public FileEntry(
            String path,
            String name,
            String mime,
            String docId,
            int flags,
            long size,
            long mtime,
            boolean writable) {

        this.path = path;
        this.name = name;
        this.mime = mime;
        this.docId = docId;
        this.flags = flags;
        this.size = size;
        this.mtime = mtime;
        this.writable = writable;
    }
}
