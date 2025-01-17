/*
 * Copyright 2024, AutoMQ CO.,LTD.
 *
 * Use of this software is governed by the Business Source License
 * included in the file BSL.md
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package com.automq.stream.s3;

import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.objects.ObjectAttributes;
import com.automq.stream.s3.operator.ObjectStorage;
import com.automq.stream.s3.operator.Writer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompositeObject is a logic object which soft links multiple objects together.
 * <p>
 * v0 format:
 * objects
 * object_count u32
 * objects (
 * object_id u64
 * block_start_index u32
 * bucket_index u16
 * )*
 * indexes
 * index_count u32
 * (
 * stream_id u64
 * start_offset u64
 * end_offset_delta u32
 * record_count u32
 * block_start_position u64
 * block_size u32
 * )*
 * index_handle
 * position u64
 * length u32
 * padding 40byte - 8 - 8 - 4
 * magic u64
 */
public class CompositeObject {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeObject.class);
    public static final byte OBJECTS_BLOCK_MAGIC = 0x52;
    public static final int OBJECT_BLOCK_HEADER_SIZE = 1 /* magic */ + 4 /* objects count */;
    public static final int OBJECT_UNIT_SIZE = 8 /* objectId */ + 4 /* blockStartIndex */ + 2 /* bucketId */;

    public static final int FOOTER_SIZE = 48;
    public static final long FOOTER_MAGIC = 0x88e241b785f4cff8L;

    public static CompositeObjectReader reader(S3ObjectMetadata objectMetadata, ObjectReader.RangeReader rangeReader) {
        return new CompositeObjectReader(objectMetadata, rangeReader);
    }

    public static CompositeObjectReader reader(S3ObjectMetadata objectMetadata, ObjectStorage objectStorage) {
        return new CompositeObjectReader(objectMetadata, (metadata, startOffset, endOffset) -> objectStorage.rangeRead(ObjectStorage.ReadOptions.DEFAULT, metadata, startOffset, endOffset));
    }

    public static CompositeObjectWriter writer(Writer writer) {
        return new CompositeObjectWriter(writer);
    }

    public static CompletableFuture<Void> delete(S3ObjectMetadata objectMetadata, ObjectStorage objectStorage) {
        @SuppressWarnings("resource")
        CompositeObjectReader reader = reader(objectMetadata, objectStorage);
        // 1. use reader to get all linked object
        return reader.basicObjectInfo().thenCompose(info -> {
            // 2. delete linked object
            List<CompositeObjectReader.ObjectIndex> objectIndexes = ((CompositeObjectReader.BasicObjectInfoExt) info).objectsBlock().indexes();
            List<S3ObjectMetadata> metadataList = objectIndexes
                .stream()
                .map(o -> new S3ObjectMetadata(o.objectId(), ObjectAttributes.builder().bucket(o.bucketId()).build().attributes()))
                .collect(Collectors.toList());
            return objectStorage.delete(metadataList)
                .thenApply(rst -> objectIndexes.stream().map(o -> o.bucketId() + "/" + o.objectId()).collect(Collectors.toList()));
        }).thenCompose(linkedObjects -> {
            // 3. delete composite object
            return objectStorage.delete(List.of(objectMetadata)).thenAccept(rst ->
                LOGGER.info("Delete composite object {}/{} success, linked objects: {}",
                    ObjectAttributes.from(objectMetadata.attributes()).bucket(), objectMetadata.objectId(), linkedObjects)
            );
        }).thenAccept(rst -> {
        }).whenComplete((rst, ex) -> {
            reader.release();
            if (ex != null) {
                LOGGER.error("Delete composite object {} fail", objectMetadata, ex);
            }
        });
    }
}
