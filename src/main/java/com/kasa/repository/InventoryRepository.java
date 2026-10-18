package com.kasa.repository;

import com.kasa.model.InventoryItem;
import com.kasa.model.InventoryMatch;

import java.util.List;

public interface InventoryRepository {
    List<InventoryMatch> findSimilar(String productName);
}
