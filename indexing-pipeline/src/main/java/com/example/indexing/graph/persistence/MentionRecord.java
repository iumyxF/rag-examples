package com.example.indexing.graph.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kg_entity_mention")
public class MentionRecord {
    @TableId
    public String id;
    public String entityId;
    public String documentId;
    public String versionId;
    public String chunkId;
    public String displayName;
    public Integer pageNumber;
    public String sheetName;
    public String sectionPath;
    public String evidenceText;
}
