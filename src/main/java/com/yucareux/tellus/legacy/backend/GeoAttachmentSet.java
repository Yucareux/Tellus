package com.yucareux.tellus.legacy.backend;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

public class GeoAttachmentSet implements Iterable<GeoAttachment<?>> {
    private final Set<GeoAttachment<?>> attachments;

    private GeoAttachmentSet(final Set<GeoAttachment<?>> attachments) {
        this.attachments = attachments;
    }

    public static GeoAttachmentSet of(final GeoAttachment<?>... attachments) {
        final Set<GeoAttachment<?>> set = new ReferenceOpenHashSet<>();
        Collections.addAll(set, attachments);
        return new GeoAttachmentSet(set);
    }

    @Override
    public Iterator<GeoAttachment<?>> iterator() {
        return attachments.iterator();
    }
}
