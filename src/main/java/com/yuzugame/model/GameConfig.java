package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GameConfig {

    private String exploreKeywords;
    private String moveKeywords;
    private String npcMentionPattern;
    private Map<String, String> outputPrefixes;
    private List<Integer> sanityWarningThresholds;
    private int stageReportInterval;
    private int fallbackPuzzleActivationTurns;
    private int autoPickupLimit;
    private String archiveMapId;
    private int archiveRevelationBonus;
    private int explorationRevelationBonus;
    private int npcDialogueRevelationBonus;
    private int npcUnlockSanityReward;
    private PuzzleRewards puzzleRewards;
    private Map<String, Integer> chapterRevelationRewards;
    private String startingMapId;
    private String startingChapter;
    private String npcGiftItemIdTemplate;
    private String npcGiftNameTemplate;
    private int npcGiftDialogueThreshold;
    private int puzzleMinRoundsBase;
    private int maxPuzzleMemoryRounds;
    private int mapAiHistoryLimit;
    private int npcAiHistoryLimit;
    private int protagonistAiHistoryLimit;

    public String getExploreKeywords() { return exploreKeywords; }
    public void setExploreKeywords(String v) { this.exploreKeywords = v; }
    public String getMoveKeywords() { return moveKeywords; }
    public void setMoveKeywords(String v) { this.moveKeywords = v; }
    public String getNpcMentionPattern() { return npcMentionPattern; }
    public void setNpcMentionPattern(String v) { this.npcMentionPattern = v; }
    public Map<String, String> getOutputPrefixes() { return outputPrefixes; }
    public void setOutputPrefixes(Map<String, String> v) { this.outputPrefixes = v; }
    public List<Integer> getSanityWarningThresholds() { return sanityWarningThresholds; }
    public void setSanityWarningThresholds(List<Integer> v) { this.sanityWarningThresholds = v; }
    public int getStageReportInterval() { return stageReportInterval; }
    public void setStageReportInterval(int v) { this.stageReportInterval = v; }
    public int getFallbackPuzzleActivationTurns() { return fallbackPuzzleActivationTurns; }
    public void setFallbackPuzzleActivationTurns(int v) { this.fallbackPuzzleActivationTurns = v; }
    public int getAutoPickupLimit() { return autoPickupLimit; }
    public void setAutoPickupLimit(int v) { this.autoPickupLimit = v; }
    public String getArchiveMapId() { return archiveMapId; }
    public void setArchiveMapId(String v) { this.archiveMapId = v; }
    public int getArchiveRevelationBonus() { return archiveRevelationBonus; }
    public void setArchiveRevelationBonus(int v) { this.archiveRevelationBonus = v; }
    public int getExplorationRevelationBonus() { return explorationRevelationBonus; }
    public void setExplorationRevelationBonus(int v) { this.explorationRevelationBonus = v; }
    public int getNpcDialogueRevelationBonus() { return npcDialogueRevelationBonus; }
    public void setNpcDialogueRevelationBonus(int v) { this.npcDialogueRevelationBonus = v; }
    public int getNpcUnlockSanityReward() { return npcUnlockSanityReward; }
    public void setNpcUnlockSanityReward(int v) { this.npcUnlockSanityReward = v; }
    public PuzzleRewards getPuzzleRewards() { return puzzleRewards; }
    public void setPuzzleRewards(PuzzleRewards v) { this.puzzleRewards = v; }
    public Map<String, Integer> getChapterRevelationRewards() { return chapterRevelationRewards; }
    public void setChapterRevelationRewards(Map<String, Integer> v) { this.chapterRevelationRewards = v; }
    public String getStartingMapId() { return startingMapId; }
    public void setStartingMapId(String v) { this.startingMapId = v; }
    public String getStartingChapter() { return startingChapter; }
    public void setStartingChapter(String v) { this.startingChapter = v; }
    public String getNpcGiftItemIdTemplate() { return npcGiftItemIdTemplate; }
    public void setNpcGiftItemIdTemplate(String v) { this.npcGiftItemIdTemplate = v; }
    public String getNpcGiftNameTemplate() { return npcGiftNameTemplate; }
    public void setNpcGiftNameTemplate(String v) { this.npcGiftNameTemplate = v; }
    public int getNpcGiftDialogueThreshold() { return npcGiftDialogueThreshold; }
    public void setNpcGiftDialogueThreshold(int v) { this.npcGiftDialogueThreshold = v; }
    public int getPuzzleMinRoundsBase() { return puzzleMinRoundsBase; }
    public void setPuzzleMinRoundsBase(int v) { this.puzzleMinRoundsBase = v; }
    public int getMaxPuzzleMemoryRounds() { return maxPuzzleMemoryRounds; }
    public void setMaxPuzzleMemoryRounds(int v) { this.maxPuzzleMemoryRounds = v; }
    public int getMapAiHistoryLimit() { return mapAiHistoryLimit; }
    public void setMapAiHistoryLimit(int v) { this.mapAiHistoryLimit = v; }
    public int getNpcAiHistoryLimit() { return npcAiHistoryLimit; }
    public void setNpcAiHistoryLimit(int v) { this.npcAiHistoryLimit = v; }
    public int getProtagonistAiHistoryLimit() { return protagonistAiHistoryLimit; }
    public void setProtagonistAiHistoryLimit(int v) { this.protagonistAiHistoryLimit = v; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PuzzleRewards {
        private Map<String, Integer> revelation;
        private Map<String, Integer> sanity;

        public Map<String, Integer> getRevelation() { return revelation; }
        public void setRevelation(Map<String, Integer> v) { this.revelation = v; }
        public Map<String, Integer> getSanity() { return sanity; }
        public void setSanity(Map<String, Integer> v) { this.sanity = v; }

        public int getRevelationReward(int difficulty) {
            if (revelation == null) return 11;
            String key = String.valueOf(difficulty);
            Integer val = revelation.get(key);
            return val != null ? val : revelation.getOrDefault("default", 11);
        }

        public int getSanityReward(int difficulty) {
            if (sanity == null) return 2;
            String key = String.valueOf(difficulty);
            Integer val = sanity.get(key);
            return val != null ? val : sanity.getOrDefault("default", 2);
        }
    }

    public int getChapterRevelationReward(String chapterId) {
        if (chapterRevelationRewards == null) return 2;
        Integer val = chapterRevelationRewards.get(chapterId);
        return val != null ? val : chapterRevelationRewards.getOrDefault("default", 2);
    }
}
