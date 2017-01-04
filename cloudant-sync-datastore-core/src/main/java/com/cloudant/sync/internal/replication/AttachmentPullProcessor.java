package com.cloudant.sync.internal.replication;

import com.cloudant.sync.documentstore.Attachment;
import com.cloudant.sync.documentstore.AttachmentException;
import com.cloudant.sync.documentstore.UnsavedStreamAttachment;
import com.cloudant.sync.internal.documentstore.PreparedAttachment;
import com.cloudant.sync.internal.mazha.CouchClient;

import java.io.InputStream;

public class AttachmentPullProcessor implements CouchClient
        .InputStreamProcessor<PreparedAttachment> {

    private final DatastoreWrapper datastoreWrapper;
    private final String contentType;
    private final Attachment.Encoding encoding;
    private final long length;
    private final long encodedLength;

    AttachmentPullProcessor(DatastoreWrapper wrapper, String name, String contentType, String
            encoding, long length, long encodedLength) {
        this.datastoreWrapper = wrapper;
        this.contentType = contentType;
        this.encoding = Attachment.getEncodingFromString(encoding);
        this.length = length;
        this.encodedLength = encodedLength;
    }

    @Override
    public PreparedAttachment processStream(InputStream stream) throws AttachmentException {
        UnsavedStreamAttachment usa = new UnsavedStreamAttachment(stream, contentType, encoding);
        PreparedAttachment attachment = datastoreWrapper.prepareAttachment(usa, length,
                encodedLength);
        return attachment;
    }
}
