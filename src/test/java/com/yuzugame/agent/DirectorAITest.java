package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.EndingRuleConfig;
import com.yuzugame.model.GameSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectorAITest {

    @Test
    void determineEndingAction_usesConfiguredRuleOrder() {
        GameSession session = new GameSession();
        session.setCurrentChapter("ch5");
        session.setTurn(100);
        session.getPlayer().setAffection(90);

        EndingRuleConfig highPriorityBroadRule = rule("HIGH_PRIORITY", 1,
                condition("currentChapter", "eq", "ch5"));
        EndingRuleConfig lowPrioritySpecificRule = rule("LOW_PRIORITY", 10,
                condition("currentChapter", "eq", "ch5"),
                condition("turn", "gte", 100),
                condition("player.affection", "gte", 80));

        DirectorAI director = new DirectorAI(null,
                new EndingRulesLoader(List.of(highPriorityBroadRule, lowPrioritySpecificRule)));

        assertEquals("HIGH_PRIORITY", director.determineEndingAction(session, null));
    }

    private static EndingRuleConfig rule(String type, int priority, EndingRuleConfig.EndingCondition... conditions) {
        EndingRuleConfig rule = new EndingRuleConfig();
        rule.setType(type);
        rule.setPriority(priority);
        rule.setLogic("AND");
        rule.setConditions(List.of(conditions));
        return rule;
    }

    private static EndingRuleConfig.EndingCondition condition(String field, String operator, Object value) {
        EndingRuleConfig.EndingCondition condition = new EndingRuleConfig.EndingCondition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    private static class EndingRulesLoader extends GameDataLoader {
        private final List<EndingRuleConfig> rules;

        EndingRulesLoader(List<EndingRuleConfig> rules) {
            this.rules = rules;
        }

        @Override
        public List<EndingRuleConfig> getEndingRules() {
            return rules;
        }
    }
}
