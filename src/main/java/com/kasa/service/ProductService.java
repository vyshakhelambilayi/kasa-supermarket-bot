package com.kasa.service;

import com.kasa.model.InventoryItem;
import com.kasa.repository.InventoryRepository;

import java.util.List;

/** Applies confidence and ambiguity rules to PostgreSQL trigram search results. */
public class ProductService {
    private static final double MINIMUM_MATCH_SCORE = 0.32;
    private static final double AMBIGUITY_GAP = 0.08;
    private final InventoryRepository inventoryRepository;

    public ProductService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public MatchResult matchInventoryItem(String englishProductName) {
        List<com.kasa.model.InventoryMatch> matches = inventoryRepository.findSimilar(englishProductName);
        if (matches.isEmpty()) return MatchResult.notFound(List.of());

        com.kasa.model.InventoryMatch best = matches.get(0);
        List<InventoryItem> alternatives = matches.stream()
                .map(com.kasa.model.InventoryMatch::item)
                .toList();

        if (best.score() < MINIMUM_MATCH_SCORE) {
            return MatchResult.notFound(alternatives);
        }

        List<com.kasa.model.InventoryMatch> closeMatches = matches.stream()
                .filter(match -> best.score() - match.score() < AMBIGUITY_GAP)
                .toList();
        if (closeMatches.size() > 1) {
            return MatchResult.ambiguous(closeMatches.stream()
                    .map(com.kasa.model.InventoryMatch::item)
                    .toList());
        }

        return MatchResult.matched(best.item(), best.score(), best.score() >= 0.999);
    }

    public record MatchResult(Status status, InventoryItem item, List<InventoryItem> alternatives,
                              double score, boolean exact) {
        public enum Status { MATCHED, AMBIGUOUS, NOT_FOUND }

        public static MatchResult matched(InventoryItem item, double score, boolean exact) {
            return new MatchResult(Status.MATCHED, item, List.of(), score, exact);
        }

        public static MatchResult ambiguous(List<InventoryItem> alternatives) {
            return new MatchResult(Status.AMBIGUOUS, null, List.copyOf(alternatives), 0, false);
        }

        public static MatchResult notFound(List<InventoryItem> alternatives) {
            return new MatchResult(Status.NOT_FOUND, null, List.copyOf(alternatives), 0, false);
        }
    }
}
