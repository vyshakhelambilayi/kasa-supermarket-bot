package com.kasa.model;

/** An inventory row and its PostgreSQL trigram similarity score. */
public record InventoryMatch(InventoryItem item, double score) { }
