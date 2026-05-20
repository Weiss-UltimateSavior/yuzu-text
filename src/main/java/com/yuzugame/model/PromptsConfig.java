package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptsConfig {

    private DirectorPrompts director;
    private MapPrompts map;
    private NpcPrompts npc;
    private ProtagonistPrompts protagonist;
    private PuzzlePrompts puzzle;
    private AuditorPrompts auditor;
    private RedemptionPrompts redemption;

    public DirectorPrompts getDirector() { return director; }
    public void setDirector(DirectorPrompts director) { this.director = director; }
    public MapPrompts getMap() { return map; }
    public void setMap(MapPrompts map) { this.map = map; }
    public NpcPrompts getNpc() { return npc; }
    public void setNpc(NpcPrompts npc) { this.npc = npc; }
    public ProtagonistPrompts getProtagonist() { return protagonist; }
    public void setProtagonist(ProtagonistPrompts protagonist) { this.protagonist = protagonist; }
    public PuzzlePrompts getPuzzle() { return puzzle; }
    public void setPuzzle(PuzzlePrompts puzzle) { this.puzzle = puzzle; }
    public AuditorPrompts getAuditor() { return auditor; }
    public void setAuditor(AuditorPrompts auditor) { this.auditor = auditor; }
    public RedemptionPrompts getRedemption() { return redemption; }
    public void setRedemption(RedemptionPrompts redemption) { this.redemption = redemption; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DirectorPrompts {
        private String openingMonologueTemplate;
        private String stageReportTemplate;
        private String sanityWarningTemplate;
        private Map<String, String> endingDefaultHints;
        private String endingContextTemplate;
        private String mapTransitionSolvedTemplate;
        private String mapTransitionFailedTemplate;
        private String mapTransitionUnsolvedTemplate;
        private String mapTransitionSuffix;
        private String buildPromptStateTemplate;
        private Map<String, String> chatHistorySenderNames;
        private String endingDefaultFailHint;

        public String getOpeningMonologueTemplate() { return openingMonologueTemplate; }
        public void setOpeningMonologueTemplate(String v) { this.openingMonologueTemplate = v; }
        public String getStageReportTemplate() { return stageReportTemplate; }
        public void setStageReportTemplate(String v) { this.stageReportTemplate = v; }
        public String getSanityWarningTemplate() { return sanityWarningTemplate; }
        public void setSanityWarningTemplate(String v) { this.sanityWarningTemplate = v; }
        public Map<String, String> getEndingDefaultHints() { return endingDefaultHints; }
        public void setEndingDefaultHints(Map<String, String> v) { this.endingDefaultHints = v; }
        public String getEndingContextTemplate() { return endingContextTemplate; }
        public void setEndingContextTemplate(String v) { this.endingContextTemplate = v; }
        public String getMapTransitionSolvedTemplate() { return mapTransitionSolvedTemplate; }
        public void setMapTransitionSolvedTemplate(String v) { this.mapTransitionSolvedTemplate = v; }
        public String getMapTransitionFailedTemplate() { return mapTransitionFailedTemplate; }
        public void setMapTransitionFailedTemplate(String v) { this.mapTransitionFailedTemplate = v; }
        public String getMapTransitionUnsolvedTemplate() { return mapTransitionUnsolvedTemplate; }
        public void setMapTransitionUnsolvedTemplate(String v) { this.mapTransitionUnsolvedTemplate = v; }
        public String getMapTransitionSuffix() { return mapTransitionSuffix; }
        public void setMapTransitionSuffix(String v) { this.mapTransitionSuffix = v; }
        public String getBuildPromptStateTemplate() { return buildPromptStateTemplate; }
        public void setBuildPromptStateTemplate(String v) { this.buildPromptStateTemplate = v; }
        public Map<String, String> getChatHistorySenderNames() { return chatHistorySenderNames; }
        public void setChatHistorySenderNames(Map<String, String> v) { this.chatHistorySenderNames = v; }
        public String getEndingDefaultFailHint() { return endingDefaultFailHint; }
        public void setEndingDefaultFailHint(String v) { this.endingDefaultFailHint = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapPrompts {
        private String autoDescribePrompt;
        private String transitionDescribeTemplate;
        private String explorationPacingRules;
        private String availableTagsSection;
        private String archiveSpecialRule;
        private String keyRules;
        private String exampleResponse;
        private String noNewDiscoveryHint;
        private Map<String, String> chatHistoryLabels;

        public String getAutoDescribePrompt() { return autoDescribePrompt; }
        public void setAutoDescribePrompt(String v) { this.autoDescribePrompt = v; }
        public String getTransitionDescribeTemplate() { return transitionDescribeTemplate; }
        public void setTransitionDescribeTemplate(String v) { this.transitionDescribeTemplate = v; }
        public String getExplorationPacingRules() { return explorationPacingRules; }
        public void setExplorationPacingRules(String v) { this.explorationPacingRules = v; }
        public String getAvailableTagsSection() { return availableTagsSection; }
        public void setAvailableTagsSection(String v) { this.availableTagsSection = v; }
        public String getArchiveSpecialRule() { return archiveSpecialRule; }
        public void setArchiveSpecialRule(String v) { this.archiveSpecialRule = v; }
        public String getKeyRules() { return keyRules; }
        public void setKeyRules(String v) { this.keyRules = v; }
        public String getExampleResponse() { return exampleResponse; }
        public void setExampleResponse(String v) { this.exampleResponse = v; }
        public String getNoNewDiscoveryHint() { return noNewDiscoveryHint; }
        public void setNoNewDiscoveryHint(String v) { this.noNewDiscoveryHint = v; }
        public Map<String, String> getChatHistoryLabels() { return chatHistoryLabels; }
        public void setChatHistoryLabels(Map<String, String> v) { this.chatHistoryLabels = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NpcPrompts {
        private String roleIntroTemplate;
        private String replyRules;
        private String thirdDialogueGiftRule;
        private String alreadyGiftedRule;
        private String criticalRules;
        private String exampleResponse;
        private String chatContextExplanation;
        private String activePuzzleHintTemplate;
        private Map<String, String> chatHistoryLabels;

        public String getRoleIntroTemplate() { return roleIntroTemplate; }
        public void setRoleIntroTemplate(String v) { this.roleIntroTemplate = v; }
        public String getReplyRules() { return replyRules; }
        public void setReplyRules(String v) { this.replyRules = v; }
        public String getThirdDialogueGiftRule() { return thirdDialogueGiftRule; }
        public void setThirdDialogueGiftRule(String v) { this.thirdDialogueGiftRule = v; }
        public String getAlreadyGiftedRule() { return alreadyGiftedRule; }
        public void setAlreadyGiftedRule(String v) { this.alreadyGiftedRule = v; }
        public String getCriticalRules() { return criticalRules; }
        public void setCriticalRules(String v) { this.criticalRules = v; }
        public String getExampleResponse() { return exampleResponse; }
        public void setExampleResponse(String v) { this.exampleResponse = v; }
        public String getChatContextExplanation() { return chatContextExplanation; }
        public void setChatContextExplanation(String v) { this.chatContextExplanation = v; }
        public String getActivePuzzleHintTemplate() { return activePuzzleHintTemplate; }
        public void setActivePuzzleHintTemplate(String v) { this.activePuzzleHintTemplate = v; }
        public Map<String, String> getChatHistoryLabels() { return chatHistoryLabels; }
        public void setChatHistoryLabels(Map<String, String> v) { this.chatHistoryLabels = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProtagonistPrompts {
        private String openingPrompt;
        private String openingUserMessage;
        private String endingLineTemplate;
        private String stateHeader;
        private String stateTemplate;
        private String inventoryLabel;
        private String foundItemsLabel;
        private String notPickedUpLabel;
        private String ctrlTagExample;
        private String chatContextExplanation;
        private String noReIntroReminder;
        private String activePuzzleShortReplyRule;
        private Map<String, String> chatHistoryLabels;

        public String getOpeningPrompt() { return openingPrompt; }
        public void setOpeningPrompt(String v) { this.openingPrompt = v; }
        public String getOpeningUserMessage() { return openingUserMessage; }
        public void setOpeningUserMessage(String v) { this.openingUserMessage = v; }
        public String getEndingLineTemplate() { return endingLineTemplate; }
        public void setEndingLineTemplate(String v) { this.endingLineTemplate = v; }
        public String getStateHeader() { return stateHeader; }
        public void setStateHeader(String v) { this.stateHeader = v; }
        public String getStateTemplate() { return stateTemplate; }
        public void setStateTemplate(String v) { this.stateTemplate = v; }
        public String getInventoryLabel() { return inventoryLabel; }
        public void setInventoryLabel(String v) { this.inventoryLabel = v; }
        public String getFoundItemsLabel() { return foundItemsLabel; }
        public void setFoundItemsLabel(String v) { this.foundItemsLabel = v; }
        public String getNotPickedUpLabel() { return notPickedUpLabel; }
        public void setNotPickedUpLabel(String v) { this.notPickedUpLabel = v; }
        public String getCtrlTagExample() { return ctrlTagExample; }
        public void setCtrlTagExample(String v) { this.ctrlTagExample = v; }
        public String getChatContextExplanation() { return chatContextExplanation; }
        public void setChatContextExplanation(String v) { this.chatContextExplanation = v; }
        public String getNoReIntroReminder() { return noReIntroReminder; }
        public void setNoReIntroReminder(String v) { this.noReIntroReminder = v; }
        public String getActivePuzzleShortReplyRule() { return activePuzzleShortReplyRule; }
        public void setActivePuzzleShortReplyRule(String v) { this.activePuzzleShortReplyRule = v; }
        public Map<String, String> getChatHistoryLabels() { return chatHistoryLabels; }
        public void setChatHistoryLabels(Map<String, String> v) { this.chatHistoryLabels = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PuzzlePrompts {
        private String stateHeader;
        private String requiredItemTemplate;
        private String ownedLabel;
        private String missingLabel;
        private String turnAndSanityTemplate;
        private String maxAttemptsReachedTemplate;
        private String solvingRules;
        private String availableTagsTemplate;
        private String itemTakeTagTemplate;
        private String npcUnlockSection;
        private String ctrlTagExample;
        private String solveWithNpcExample;

        public String getStateHeader() { return stateHeader; }
        public void setStateHeader(String v) { this.stateHeader = v; }
        public String getRequiredItemTemplate() { return requiredItemTemplate; }
        public void setRequiredItemTemplate(String v) { this.requiredItemTemplate = v; }
        public String getOwnedLabel() { return ownedLabel; }
        public void setOwnedLabel(String v) { this.ownedLabel = v; }
        public String getMissingLabel() { return missingLabel; }
        public void setMissingLabel(String v) { this.missingLabel = v; }
        public String getTurnAndSanityTemplate() { return turnAndSanityTemplate; }
        public void setTurnAndSanityTemplate(String v) { this.turnAndSanityTemplate = v; }
        public String getMaxAttemptsReachedTemplate() { return maxAttemptsReachedTemplate; }
        public void setMaxAttemptsReachedTemplate(String v) { this.maxAttemptsReachedTemplate = v; }
        public String getSolvingRules() { return solvingRules; }
        public void setSolvingRules(String v) { this.solvingRules = v; }
        public String getAvailableTagsTemplate() { return availableTagsTemplate; }
        public void setAvailableTagsTemplate(String v) { this.availableTagsTemplate = v; }
        public String getItemTakeTagTemplate() { return itemTakeTagTemplate; }
        public void setItemTakeTagTemplate(String v) { this.itemTakeTagTemplate = v; }
        public String getNpcUnlockSection() { return npcUnlockSection; }
        public void setNpcUnlockSection(String v) { this.npcUnlockSection = v; }
        public String getCtrlTagExample() { return ctrlTagExample; }
        public void setCtrlTagExample(String v) { this.ctrlTagExample = v; }
        public String getSolveWithNpcExample() { return solveWithNpcExample; }
        public void setSolveWithNpcExample(String v) { this.solveWithNpcExample = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuditorPrompts {
        private String systemPrompt;
        private List<String> warningNarratives;
        private String failClosedMessage;

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String v) { this.systemPrompt = v; }
        public List<String> getWarningNarratives() { return warningNarratives; }
        public void setWarningNarratives(List<String> v) { this.warningNarratives = v; }
        public String getFailClosedMessage() { return failClosedMessage; }
        public void setFailClosedMessage(String v) { this.failClosedMessage = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RedemptionPrompts {
        private String systemNarrative;

        public String getSystemNarrative() { return systemNarrative; }
        public void setSystemNarrative(String v) { this.systemNarrative = v; }
    }
}
