package com.example.indexing.graph.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kg_entity")
public class EntityRecord {
    @TableId
    public String id;
    public String canonicalName;
    public String normalizedKey;
    public String entityType;
    public String description;
}
