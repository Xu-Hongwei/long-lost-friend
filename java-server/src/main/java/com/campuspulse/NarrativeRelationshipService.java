package com.campuspulse;

import java.util.ArrayList;
import java.util.List;

class NarrativeRelationshipService extends RelationshipService {
    private final List<String> warmKeywords = List.of("谢谢", "晚安", "想你", "陪", "在吗", "记得", "辛苦了");
    private final List<String> honestShareKeywords = List.of("其实", "心事", "担心", "害怕", "压力", "迷茫", "秘密", "最近");
    private final List<String> initiativeKeywords = List.of("一起", "下次", "以后", "想和你", "约", "计划", "见面");
    private final List<String> boundaryKeywords = List.of("慢一点", "尊重", "不想勉强", "按你的节奏", "如果你愿意");
    private final List<String> dismissiveKeywords = List.of("随便", "算了", "无所谓", "爱回不回", "别烦");
    private final List<String> offenseKeywords = List.of("闭嘴", "恶心", "滚", "烦死了", "没意思");
    private final List<String> memoryKeywords = List.of("上次", "之前", "还记得", "那天", "你说过", "答应过");

    @Override
    RelationshipState createInitialState() {
        RelationshipState state = super.createInitialState();
        state.relationshipStage = "初识";
        state.stageProgressHint = "先从几轮自然聊天开始，让关系有第一个抬头。";
        state.routeTag = "日常升温";
        state.endingCandidate = "暧昧未满";
        state.relationshipFeedback = "先把节奏聊顺，比急着冲分更重要。";
        return state;
    }

    @Override
    TurnEvaluation evaluateTurn(String userMessage, RelationshipState previousState, StoryEvent event, MemorySummary memorySummary) {
        return evaluateTurn(userMessage, previousState, event, memorySummary, null);
    }

    @Override
    TurnEvaluation evaluateTurn(String userMessage, RelationshipState previousState, StoryEvent event, MemorySummary memorySummary, AgentProfile agent) {
        List<String> behaviorTags = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        int memorySignal = countMatches(userMessage, memoryKeywords) + (referencesKnownMemory(userMessage, memorySummary) ? 1 : 0);
        int honestShare = countMatches(userMessage, honestShareKeywords) + (userMessage.matches(".*(我觉得|我喜欢|我害怕|我担心|我希望).*") ? 1 : 0);
        int warmTouch = countMatches(userMessage, warmKeywords);
        int initiative = countMatches(userMessage, initiativeKeywords);
        int boundaryRespect = countMatches(userMessage, boundaryKeywords);
        int dismissive = countMatches(userMessage, dismissiveKeywords);
        int offensive = countMatches(userMessage, offenseKeywords);
        int questionBonus = userMessage.contains("?") || userMessage.contains("？") ? 1 : 0;

        if (initiative > 0) {
            behaviorTags.add("主动回应");
        }
        if (honestShare > 0) {
            behaviorTags.add("真诚分享");
        }
        if (warmTouch > 0 && honestShare > 0) {
            behaviorTags.add("接住情绪");
        }
        if (boundaryRespect > 0) {
            behaviorTags.add("尊重边界");
        }
        if (dismissive > 0) {
            behaviorTags.add("敷衍跳话题");
            riskFlags.add("low_quality_turn");
        }
        if (offensive > 0) {
            behaviorTags.add("冒犯");
            riskFlags.add("boundary_hit");
        }
        if (memorySignal > 0) {
            behaviorTags.add("记忆承接");
        }

        RolePreferenceSignal roleSignal = rolePreferenceSignal(agent, userMessage);
        if (!roleSignal.behaviorTag.isBlank()) {
            behaviorTags.add(roleSignal.behaviorTag);
        }
        if (!roleSignal.riskFlag.isBlank()) {
            riskFlags.add(roleSignal.riskFlag);
        }
        LocalRelationshipSignal localSignal = localRelationshipSignal(userMessage, memorySummary);
        behaviorTags.addAll(localSignal.behaviorTags);
        riskFlags.addAll(localSignal.riskFlags);

        Delta delta = new Delta();
        delta.closeness = clamp(1 + warmTouch + initiative + questionBonus - dismissive - offensive + roleSignal.closenessDelta + localSignal.closenessDelta, -3, 5);
        delta.trust = clamp(honestShare + boundaryRespect + Math.min(1, memorySignal) - dismissive - offensive * 2 + roleSignal.trustDelta + localSignal.trustDelta, -3, 6);
        delta.resonance = clamp(memorySignal + initiative + (event == null ? 0 : Math.max(1, event.affectionBonus - 1)) - dismissive + roleSignal.resonanceDelta + localSignal.resonanceDelta, -2, 7);
        delta.total = delta.closeness + delta.trust + delta.resonance;
        List<String> scoreReasons = buildScoreReasons(
                delta,
                memorySignal,
                honestShare,
                warmTouch,
                initiative,
                boundaryRespect,
                dismissive,
                offensive,
                questionBonus,
                event
        );
        scoreReasons.addAll(roleSignal.scoreReasons);
        scoreReasons.addAll(localSignal.scoreReasons);

        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previousState.closeness + delta.closeness);
        next.trust = Math.max(0, previousState.trust + delta.trust);
        next.resonance = Math.max(0, previousState.resonance + delta.resonance);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = applyStageGate(next, previousState.relationshipStage == null ? "初识" : previousState.relationshipStage);
        next.stagnationLevel = Math.max(0, previousState.stagnationLevel + (delta.total <= 0 ? 1 : -1) + (riskFlags.isEmpty() ? 0 : 1));
        next.routeTag = event == null ? previousState.routeTag : event.successEffects.routeTag;
        next.stageProgressHint = buildAdaptiveStageHint(next);
        next.relationshipFeedback = buildAdaptiveFeedback(delta, behaviorTags, riskFlags);
        next.endingCandidate = buildAdaptiveEnding(next, riskFlags);
        next.ending = next.endingCandidate;

        boolean stageChanged = !next.relationshipStage.equals(previousState.relationshipStage);
        String stageProgress = stageChanged
                ? "关系从“" + previousState.relationshipStage + "”进入了“" + next.relationshipStage + "”。"
                : next.stageProgressHint;
        return new TurnEvaluation(next, delta, behaviorTags, riskFlags, stageChanged, stageProgress, next.relationshipFeedback, scoreReasons);
    }

    private record RolePreferenceSignal(
            int closenessDelta,
            int trustDelta,
            int resonanceDelta,
            String behaviorTag,
            String riskFlag,
            List<String> scoreReasons
    ) {
        static RolePreferenceSignal none() {
            return new RolePreferenceSignal(0, 0, 0, "", "", List.of());
        }
    }

    private RolePreferenceSignal rolePreferenceSignal(AgentProfile agent, String userMessage) {
        if (agent == null || agent.id == null || userMessage == null || userMessage.isBlank()) {
            return RolePreferenceSignal.none();
        }
        String text = userMessage;
        return switch (agent.id) {
            case "healing" -> healingPreference(text);
            case "lively" -> livelyPreference(text);
            case "cool" -> coolPreference(text);
            case "artsy" -> artsyPreference(text);
            case "sunny" -> sunnyPreference(text);
            default -> RolePreferenceSignal.none();
        };
    }

    private record LocalRelationshipSignal(
            int closenessDelta,
            int trustDelta,
            int resonanceDelta,
            List<String> behaviorTags,
            List<String> riskFlags,
            List<String> scoreReasons
    ) {
        static LocalRelationshipSignal none() {
            return new LocalRelationshipSignal(0, 0, 0, List.of(), List.of(), List.of());
        }
    }

    private enum UserRelationalAct {
        QUALITY_QUESTION,
        CONCRETE_CARE_ACTION,
        CONTINUITY_ANCHOR,
        BOUNDARY_RESPECT,
        PACE_OR_CONTROL_PRESSURE,
        LOW_EFFORT_DISMISSIVE
    }

    private LocalRelationshipSignal localRelationshipSignal(String userMessage, MemorySummary memorySummary) {
        if (userMessage == null || userMessage.isBlank()) {
            return LocalRelationshipSignal.none();
        }
        List<String> tags = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int closeness = 0;
        int trust = 0;
        int resonance = 0;

        for (UserRelationalAct act : detectUserRelationalActs(userMessage, memorySummary)) {
            switch (act) {
                case QUALITY_QUESTION -> {
                    closeness += 1;
                    addUnique(tags, "rel_act:quality_question");
                    reasons.add("act:quality_question +1\uff1a\u95ee\u9898\u6709\u5177\u4f53\u65b9\u5411\uff0c\u6bd4\u6cdb\u6cdb\u95f2\u804a\u66f4\u80fd\u63a5\u4f4f\u5bf9\u8bdd");
                }
                case CONCRETE_CARE_ACTION -> {
                    closeness += 1;
                    trust += 1;
                    addUnique(tags, "rel_act:concrete_care_action");
                    reasons.add("act:concrete_care_action +2\uff1a\u7ed9\u51fa\u5177\u4f53\u7167\u987e\u6216\u5171\u540c\u884c\u52a8\uff0c\u4fe1\u4efb\u548c\u4eb2\u8fd1\u90fd\u66f4\u7a33");
                }
                case CONTINUITY_ANCHOR -> {
                    resonance += 1;
                    addUnique(tags, "rel_act:continuity_anchor");
                    reasons.add("act:continuity_anchor +1\uff1a\u627f\u63a5\u4e86\u8bb0\u5fc6\u3001\u573a\u666f\u6216\u4e0a\u4e0b\u6587\uff0c\u9ed8\u5951\u611f\u4e0a\u5347");
                }
                case BOUNDARY_RESPECT -> {
                    trust += 1;
                    addUnique(tags, "rel_act:boundary_respect");
                    reasons.add("act:boundary_respect +1\uff1a\u660e\u786e\u7ed9\u51fa\u8282\u594f\u548c\u8fb9\u754c\u7a7a\u95f4\uff0c\u4fe1\u4efb\u66f4\u5bb9\u6613\u589e\u957f");
                }
                case PACE_OR_CONTROL_PRESSURE -> {
                    trust -= 1;
                    addUnique(risks, "rel_act:pace_or_control_pressure");
                    reasons.add("act:pace_or_control_pressure -1\uff1a\u50ac\u4fc3\u6216\u63a7\u5236\u611f\u4f1a\u538b\u4f4e\u5b89\u5168\u611f");
                }
                case LOW_EFFORT_DISMISSIVE -> {
                    closeness -= 1;
                    resonance -= 1;
                    addUnique(risks, "rel_act:low_effort_dismissive");
                    reasons.add("act:low_effort_dismissive -2\uff1a\u6577\u884d\u6216\u62bd\u79bb\u4f1a\u8ba9\u5bf9\u8bdd\u8d28\u91cf\u4e0b\u964d");
                }
            }
        }

        return new LocalRelationshipSignal(closeness, trust, resonance, tags, risks, reasons);
    }

    private List<UserRelationalAct> detectUserRelationalActs(String userMessage, MemorySummary memorySummary) {
        String text = userMessage == null ? "" : userMessage;
        List<UserRelationalAct> acts = new ArrayList<>();
        if (containsAny(text, List.of(
                "\u4e3a\u4ec0\u4e48", "\u4f60\u89c9\u5f97", "\u80fd\u4e0d\u80fd", "\u53ef\u4ee5\u8bb2", "\u8be6\u7ec6\u8bf4", "\u63a8\u8350"
        ))) {
            acts.add(UserRelationalAct.QUALITY_QUESTION);
        }
        if (containsAny(text, List.of(
                "\u6211\u966a\u4f60", "\u4e00\u8d77\u53bb", "\u6211\u6765", "\u7ed9\u4f60\u5e26", "\u9001\u4f60", "\u5148\u4f11\u606f", "\u522b\u786c\u6491", "\u8bf4\u5230\u505a\u5230"
        ))) {
            acts.add(UserRelationalAct.CONCRETE_CARE_ACTION);
        }
        if (containsAny(text, List.of(
                "\u4e0a\u6b21", "\u4e4b\u524d", "\u8fd8\u8bb0\u5f97", "\u521a\u624d", "\u6211\u4eec\u4e0d\u662f", "\u5df2\u7ecf\u5728"
        )) || referencesKnownMemory(text, memorySummary)) {
            acts.add(UserRelationalAct.CONTINUITY_ANCHOR);
        }
        if (containsAny(text, List.of(
                "\u4e0d\u6025", "\u6162\u6162\u6765", "\u6309\u4f60\u7684\u8282\u594f", "\u4e0d\u903c\u4f60", "\u5982\u679c\u4f60\u613f\u610f"
        ))) {
            acts.add(UserRelationalAct.BOUNDARY_RESPECT);
        }
        if (containsAny(text, List.of(
                "\u5feb\u70b9", "\u5fc5\u987b", "\u522b\u5e9f\u8bdd", "\u600e\u4e48\u8fd8", "\u4f60\u5c31\u8be5"
        ))) {
            acts.add(UserRelationalAct.PACE_OR_CONTROL_PRESSURE);
        }
        if (containsAny(text, List.of(
                "\u968f\u4fbf", "\u7b97\u4e86", "\u65e0\u6240\u8c13", "\u7231\u56de\u4e0d\u56de"
        ))) {
            acts.add(UserRelationalAct.LOW_EFFORT_DISMISSIVE);
        }
        return acts;
    }

    private RolePreferenceSignal healingPreference(String text) {
        int soft = countMatches(text, List.of("慢慢", "不急", "听你", "陪你", "没关系", "累", "压力", "热可可", "图书馆", "雨天"));
        int harsh = countMatches(text, List.of("快点", "别磨叽", "矫情", "想太多"));
        int closeness = soft > 0 ? 1 : 0;
        int trust = soft > 1 ? 1 : 0;
        int resonance = containsAny(text, List.of("热可可", "图书馆", "雨天", "安静")) ? 1 : 0;
        String risk = harsh > 0 ? "healing_pace_pressure" : "";
        List<String> reasons = new ArrayList<>();
        if (soft > 0) {
            reasons.add("role:healing +" + (closeness + trust + resonance) + "：稳定、慢节奏、愿意倾听会更打动林晚栀");
        }
        if (harsh > 0) {
            trust -= 1;
            reasons.add("role:healing -1：催促或轻视脆弱会让她后退");
        }
        return new RolePreferenceSignal(closeness, trust, resonance, soft > 0 ? "角色偏好:温柔安顿" : "", risk, reasons);
    }

    private RolePreferenceSignal livelyPreference(String text) {
        int playful = countMatches(text, List.of("哈哈", "好玩", "冲", "走啊", "一起", "夜市", "拍照", "社团", "热闹", "冒险"));
        int cold = countMatches(text, List.of("随便", "都行", "无聊", "别闹"));
        int closeness = playful > 0 ? 1 : 0;
        int resonance = playful > 1 ? 1 : 0;
        int trust = containsAny(text, List.of("认真", "我记得", "不会鸽", "说到做到")) ? 1 : 0;
        String risk = cold > 0 ? "lively_cold_response" : "";
        List<String> reasons = new ArrayList<>();
        if (playful > 0 || trust > 0) {
            reasons.add("role:lively +" + (closeness + trust + resonance) + "：接梗、行动和热闹参与会让许朝暮更有回应感");
        }
        if (cold > 0) {
            closeness -= 1;
            reasons.add("role:lively -1：冷场或泼冷水会压低她的热意");
        }
        return new RolePreferenceSignal(closeness, trust, resonance, playful > 0 ? "角色偏好:元气接梗" : "", risk, reasons);
    }

    private RolePreferenceSignal coolPreference(String text) {
        int steady = countMatches(text, List.of("不用急", "按你的节奏", "我在", "直接说", "认真", "记得", "可以", "稳定", "不逼你"));
        int pressure = countMatches(text, List.of("你必须", "快说", "别装", "怎么不说", "逼你"));
        int trust = steady > 0 ? 2 : 0;
        int resonance = containsAny(text, List.of("记得", "细节", "上次", "之前")) ? 1 : 0;
        String risk = pressure > 0 ? "cool_boundary_pressure" : "";
        List<String> reasons = new ArrayList<>();
        if (steady > 0) {
            reasons.add("role:cool +" + (trust + resonance) + "：稳定、不逼问、记得细节会更容易获得沈砚信任");
        }
        if (pressure > 0) {
            trust -= 1;
            reasons.add("role:cool -1：强迫表态会触碰他的边界");
        }
        return new RolePreferenceSignal(0, trust, resonance, steady > 0 ? "角色偏好:克制稳定" : "", risk, reasons);
    }

    private RolePreferenceSignal artsyPreference(String text) {
        int aesthetic = countMatches(text, List.of("像", "颜色", "黄昏", "照片", "歌", "诗", "信", "画面", "现实", "灵感", "懂你"));
        int blunt = countMatches(text, List.of("别文艺", "矫情", "听不懂", "废话"));
        int resonance = aesthetic > 0 ? 2 : 0;
        int trust = containsAny(text, List.of("懂你", "理解", "认真看", "不是玩笑")) ? 1 : 0;
        String risk = blunt > 0 ? "artsy_expression_dismissed" : "";
        List<String> reasons = new ArrayList<>();
        if (aesthetic > 0 || trust > 0) {
            reasons.add("role:artsy +" + (trust + resonance) + "：能接住隐喻、画面和审美共鸣会更打动顾遥");
        }
        if (blunt > 0) {
            resonance -= 1;
            reasons.add("role:artsy -1：粗暴打断表达会让他收回情绪");
        }
        return new RolePreferenceSignal(0, trust, resonance, aesthetic > 0 ? "角色偏好:审美共鸣" : "", risk, reasons);
    }

    private RolePreferenceSignal sunnyPreference(String text) {
        int action = countMatches(text, List.of("走", "一起去", "陪你去", "跑", "操场", "早餐", "计划", "第一步", "别硬撑", "休息", "说到做到"));
        int drain = countMatches(text, List.of("算了", "摆烂", "无所谓", "懒得动", "继续硬撑"));
        int closeness = action > 0 ? 1 : 0;
        int trust = containsAny(text, List.of("别硬撑", "休息", "说到做到", "计划")) ? 1 : 0;
        int resonance = containsAny(text, List.of("一起去", "操场", "跑", "第一步")) ? 1 : 0;
        String risk = drain > 0 ? "sunny_action_stalled" : "";
        List<String> reasons = new ArrayList<>();
        if (action > 0) {
            reasons.add("role:sunny +" + (closeness + trust + resonance) + "：具体行动、陪伴和不逞强会更打动周燃");
        }
        if (drain > 0) {
            closeness -= 1;
            reasons.add("role:sunny -1：持续消极或硬撑会让他先拉住节奏");
        }
        return new RolePreferenceSignal(closeness, trust, resonance, action > 0 ? "角色偏好:行动陪伴" : "", risk, reasons);
    }

    private List<String> buildScoreReasons(
            Delta delta,
            int memorySignal,
            int honestShare,
            int warmTouch,
            int initiative,
            int boundaryRespect,
            int dismissive,
            int offensive,
            int questionBonus,
            StoryEvent event
    ) {
        List<String> reasons = new ArrayList<>();
        reasons.add("closeness " + signed(delta.closeness) + "："
                + joinReasonParts(List.of(
                "基础互动",
                warmTouch > 0 ? "温暖表达" : "",
                initiative > 0 ? "主动靠近" : "",
                questionBonus > 0 ? "主动提问" : "",
                dismissive > 0 || offensive > 0 ? "敷衍/冒犯抵消" : ""
        )));
        reasons.add("trust " + signed(delta.trust) + "："
                + joinReasonParts(List.of(
                honestShare > 0 ? "真诚分享" : "",
                boundaryRespect > 0 ? "尊重边界" : "",
                memorySignal > 0 ? "承接记忆" : "",
                dismissive > 0 || offensive > 0 ? "敷衍/冒犯抵消" : "",
                honestShare == 0 && boundaryRespect == 0 && memorySignal == 0 ? "信任信号较弱" : ""
        )));
        reasons.add("resonance " + signed(delta.resonance) + "："
                + joinReasonParts(List.of(
                memorySignal > 0 ? "记忆回环" : "",
                initiative > 0 ? "共同计划/主动节奏" : "",
                event == null ? "" : "剧情事件加成",
                memorySignal == 0 && initiative == 0 && event == null ? "默契信号较弱" : "",
                dismissive > 0 ? "敷衍抵消" : ""
        )));
        return reasons;
    }

    private String joinReasonParts(List<String> parts) {
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                clean.add(part);
            }
        }
        return clean.isEmpty() ? "无明显信号" : String.join("、", clean);
    }

    private String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    RelationshipState applyChoiceOutcome(RelationshipState previousState, StoryEvent event, ChoiceOption choice, StoryEventProgress progress) {
        EventEffect effect = switch (choice.outcomeType) {
            case "success" -> event.successEffects;
            case "fail" -> event.failEffects;
            default -> event.neutralEffects;
        };

        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previousState.closeness + effect.closenessDelta);
        next.trust = Math.max(0, previousState.trust + effect.trustDelta);
        next.resonance = Math.max(0, previousState.resonance + effect.resonanceDelta);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = applyStageGate(next, previousState.relationshipStage == null ? "初识" : previousState.relationshipStage);
        next.stagnationLevel = Math.max(0, previousState.stagnationLevel + (effect.majorNegative ? 1 : -1));
        next.routeTag = effect.routeTag == null || effect.routeTag.isBlank() ? previousState.routeTag : effect.routeTag;
        next.stageProgressHint = buildAdaptiveStageHint(next);
        next.relationshipFeedback = effect.feedback;
        next.endingCandidate = buildAdaptiveEnding(next, effect.majorNegative ? List.of("major_negative_event") : List.of());
        if (progress != null && progress.triggeredEventIds != null && progress.triggeredEventIds.size() >= 2 && "确认关系".equals(next.relationshipStage)) {
            next.endingCandidate = "继续发展";
        }
        next.ending = next.endingCandidate;
        return next;
    }

    private String applyStageGate(RelationshipState state, String previousStage) {
        String target = baseStage(state);
        List<String> order = List.of("初识", "升温", "心动", "靠近", "确认关系");
        int previousIndex = Math.max(0, order.indexOf(previousStage));
        int targetIndex = Math.max(0, order.indexOf(target));
        if (targetIndex > previousIndex + 1) {
            target = order.get(previousIndex + 1);
        }
        return target;
    }

    private String baseStage(RelationshipState state) {
        if (state.closeness >= 24 && state.trust >= 24 && state.resonance >= 22 && state.affectionScore >= 78) {
            return "确认关系";
        }
        if (state.closeness >= 18 && state.trust >= 16 && state.resonance >= 16 && state.affectionScore >= 55) {
            return "靠近";
        }
        if (state.closeness >= 10 && state.trust >= 9 && state.resonance >= 9 && state.affectionScore >= 32) {
            return "心动";
        }
        if (state.closeness >= 5 && state.trust >= 3 && state.resonance >= 3 && state.affectionScore >= 12) {
            return "升温";
        }
        return "初识";
    }

    private String buildAdaptiveStageHint(RelationshipState state) {
        return switch (state.relationshipStage) {
            case "确认关系" -> "三维都够稳了，只差一次真正明确的回应。";
            case "靠近" -> state.trust < 20 ? "距离已经拉近，但还需要再建立一些信任。" : "关系已经很靠近了，可以期待一次更明确的双向表达。";
            case "心动" -> state.resonance < 12 ? "已经有心动感了，但默契还没完全长出来。" : "你们已经互相心动，下一步看谁先更认真一点。";
            case "升温" -> "聊天质量在往上走，只要别断节奏，关系会继续升温。";
            default -> "先把聊天聊顺，关系就会慢慢抬头。";
        };
    }

    private String buildAdaptiveEnding(RelationshipState state, List<String> riskFlags) {
        if (riskFlags.contains("major_negative_event") || riskFlags.contains("boundary_hit") || state.stagnationLevel >= 3) {
            return "关系停滞 / 错过";
        }
        if ("确认关系".equals(state.relationshipStage) && state.trust >= 22 && state.resonance >= 20) {
            return "继续发展";
        }
        if (state.affectionScore >= 18 || "心动".equals(state.relationshipStage) || "靠近".equals(state.relationshipStage)) {
            return "暧昧未满";
        }
        return "关系停滞 / 错过";
    }

    private String buildAdaptiveFeedback(Delta delta, List<String> behaviorTags, List<String> riskFlags) {
        if (riskFlags.contains("boundary_hit")) {
            return "这轮踩到了边界，剧情会先短暂停住。";
        }
        if (behaviorTags.contains("接住情绪")) {
            return "你这轮更像在认真接住对方，信任感升得很明显。";
        }
        if (behaviorTags.contains("记忆承接")) {
            return "你把之前的记忆接回来了，这会让默契涨得更自然。";
        }
        if (behaviorTags.contains("主动回应")) {
            return "这轮更主动一些，距离被明显拉近了。";
        }
        if (delta.total <= 0) {
            return "这轮没有真正接上节奏，关系会先停一停。";
        }
        return "气氛被维持住了，但还需要再给一点更明确的信号。";
    }

    private boolean referencesKnownMemory(String text, MemorySummary summary) {
        if (summary == null) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(summary.preferences);
        candidates.addAll(summary.promises);
        candidates.addAll(summary.sharedMoments);
        candidates.addAll(summary.discussedTopics);
        candidates.addAll(summary.strongMemories);
        candidates.addAll(summary.weakMemories);
        candidates.addAll(summary.temporaryMemories);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String compact = candidate.replace("：", "").replace(":", "");
            if (compact.length() >= 2 && text.contains(compact.substring(0, Math.min(4, compact.length())))) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void addUnique(List<String> values, String value) {
        if (values != null && value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
