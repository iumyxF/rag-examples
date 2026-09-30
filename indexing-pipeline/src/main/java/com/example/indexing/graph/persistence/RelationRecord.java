package com.example.indexing.graph.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kg_relation")
public class RelationRecord {
    @TableId
    public String id;
    public String sourceEntityId;
    public String targetEntityId;
    public String relationType;
    public String rawRelation;
    public double confidence;
    public String documentId;
    public String versionId;
    public String chunkId;
    public String evidenceText;
}
