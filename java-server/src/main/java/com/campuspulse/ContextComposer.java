package com.campuspulse;

import java.util.ArrayList;
import java.util.List;

class ContextComposer {
    private static final int PROMPT_SLOT_TOKEN_BUDGET = 3200;

    PromptBundle compose(LlmRequest request) {
        PromptBundle bundle = buildPromptBundleSlots(request);
        bundle.applyBudget(PROMPT_SLOT_TOKEN_BUDGET);
        return bundle;
    }

    private PromptBundle buildPromptBundleSlots(LlmRequest request) {
        String summaryText = blankTo(request.longTermSummary, "暂无长期记忆。");
        String recallText = blankTo(request.recalledMemoryText, "暂无高相关记忆。");
        String recallTier = blankTo(request.recalledMemoryTier, "none");
        String eventText = request.event == null ? "本轮没有关键事件触发。" : request.event.title + "：" + request.event.theme;
        String timeText = request.timeContext == null
                ? "暂无时间上下文。"
                : request.timeContext.dayPart + "，当地时间 " + request.timeContext.localTime + "。" + blankTo(request.timeContext.frame, "");
        String weatherText = request.weatherContext == null || request.weatherContext.city == null || request.weatherContext.city.isBlank()
                ? "暂无天气上下文。"
                : request.weatherContext.city + " 当前天气：" + blankTo(request.weatherContext.summary, "未获取到天气描述")
                + (request.weatherContext.temperatureC == null ? "" : "，" + request.weatherContext.temperatureC + "°C")
                + (request.weatherContext.live ? "（实时）" : "（缓存或回退）");
        String sceneText = blankTo(request.sceneFrame, "当前场景还在自然铺开。");
        String emotionText = request.emotionState == null
                ? "暂无情感状态。"
                : "warmth=" + request.emotionState.warmth
                + ", safety=" + request.emotionState.safety
                + ", longing=" + request.emotionState.longing
                + ", initiative=" + request.emotionState.initiative
                + ", vulnerability=" + request.emotionState.vulnerability
                + ", mood=" + blankTo(request.emotionState.currentMood, "calm");
        String memoryPlanText = request.memoryUsePlan == null
                ? "暂无记忆使用计划。"
                : "模式=" + blankTo(request.memoryUsePlan.useMode, "hold")
                + "；原因=" + blankTo(request.memoryUsePlan.relevanceReason, "无")
                + "；候选记忆=" + (request.memoryUsePlan.selectedMemories.isEmpty() ? "无" : String.join(" | ", request.memoryUsePlan.selectedMemories));

        String continuityText = buildContinuityText(request.dialogueContinuityState);
        String turnContextText = buildTurnContextText(request.turnContext);
        String sessionMemoryText = buildSessionMemoryPaneText(request.memoryPaneState);
        String backstoryText = buildBackstoryText(request.agent);
        String voiceProfileText = buildVoiceProfileText(request.agent);
        String personaScenarioText = buildPersonaScenarioText(request.agent, request.sceneState);
        String personaExamplesText = buildPersonaExamplesText(request.agent);
        String worldInfoText = buildWorldInfoText(request.worldInfoActivations);
        String structuredSceneRule = buildStructuredSceneRule(request);
        String repairCueText = buildRepairCueText(request.pendingRepairCue);

        PromptBundle bundle = new PromptBundle();
        bundle.add(slot("persona.identity",
                "你正在扮演大学校园恋爱互动游戏中的角色“" + request.agent.name + "”（" + request.agent.archetype + "）。\n"
                        + "角色性别与代词：" + request.agent.gender + "，第三人称统一使用“" + request.agent.subjectPronoun + "”。",
                100));
        bundle.add(slot("persona.core",
                buildPersonaCoreText(request.agent),
                99));
        bundle.add(slot("persona.voice",
                buildPersonaVoiceContractText(request.agent, voiceProfileText),
                99));
        bundle.add(slot("persona.preferences",
                buildPersonaPreferenceText(request.agent),
                97));
        bundle.add(slot("persona.boundary-details",
                buildPersonaBoundaryDetailsText(request.agent),
                97));
        bundle.add(slot("persona.profile",
                "角色具体背景只作稳定底色，不要主动像简历一样全部报出：" + backstoryText,
                89));
        bundle.add(slot("persona.scenario",
                personaScenarioText,
                94));
        bundle.add(slot("persona.examples",
                personaExamplesText,
                "BEFORE_HISTORY", "system", 2, 96));
        bundle.add(slot("relationship.state",
                "当前关系阶段：" + request.relationshipState.relationshipStage + "，当前总好感：" + request.relationshipState.affectionScore,
                86));
        bundle.add(slot("runtime.turn",
                "当前回复来源：" + blankTo(request.replySource, "user_turn") + "\n"
                        + "用户当前情绪：" + blankTo(request.currentUserMood, "neutral") + "\n"
                        + "本轮回复节奏：" + blankTo(request.responseCadence, "steady_flow"),
                82));
        bundle.add(slot("runtime.scene",
                "时间上下文：" + timeText + "\n"
                        + "天气上下文：" + weatherText + "\n"
                        + "当前场景：" + sceneText + "\n"
                        + structuredSceneRule
                        + "角色当前情感状态：" + emotionText,
                82));
        bundle.add(slot("memory.summary",
                "长期记忆摘要：" + summaryText,
                74));
        bundle.add(slot("memory.recall",
                "优先召回的记忆层级：" + recallTier + "\n"
                        + "高相关记忆：" + recallText,
                88));
        bundle.add(slot("memory.session",
                "会话工作记忆窗格（优先于长期摘要，用于保持当前几轮连续性）：" + sessionMemoryText,
                "BEFORE_HISTORY", "system", 0, 92));
        bundle.add(slot("memory.directive",
                "记忆使用计划：" + memoryPlanText,
                82));
        bundle.add(slot("world.info",
                worldInfoText,
                "BEFORE_HISTORY", "system", 0, 78));
        bundle.add(slot("turn.understanding",
                "上下文智能层：" + continuityText + "\n"
                        + "本轮结构化理解：" + turnContextText + "\n"
                        + repairCueText,
                95));
        bundle.add(slot("plot.directive",
                "当前事件：" + eventText + "\n"
                        + "本轮回应策略：" + blankTo(request.responseDirective, "保持角色一致，顺着当前聊天自然展开。"),
                84));
        bundle.add(slot("persona.boundaries",
                "边界：" + String.join("；", request.agent.boundaries),
                98));
        bundle.add(slot("response.rules",
                "要求：\n"
                        + "1. 只输出角色回复本身。\n"
                        + "2. 本地理解只提供 turnMissionCandidates 候选；localGuards 才是硬护栏。若 localGuards 要求先回答、先修正或别替换当前话题，第一句必须先满足它。\n"
                        + "3. 如果用户这轮问了明确问题，第一句必须先直接回答问题，不能先演动作、先回忆剧情、先抒情。\n"
                        + "4. 场景推进和记忆带回只能放在回答当前问题之后，而且要轻，不要抢走当下这句话的重心。\n"
                        + "5. 不要永远一问一答，但也不要为了推进剧情而忽略用户刚刚抛来的球。\n"
                        + "6. 普通回复通常 2 到 4 句；静默心跳或长聊心跳这类主动消息通常 1 到 2 句，更轻、更像临时想起对方。\n"
                        + "7. 如果 reply_source 是 plot_push，表示当前对话里顺势往前走半步，不是切到聊天外发消息，不要写成“给你发消息”“看到你回复”“屏幕那头”这种异步联系口吻。\n"
                        + "8. 如果 reply_source 是 silence_heartbeat 或 long_chat_heartbeat，才说明这是角色主动发出的轻消息；要像顺手接话，不像系统提醒。\n"
                        + "9. 如果用户输入很短，不要把压力丢回给用户，由你主动给一个容易接的话头。\n"
                        + "10. 如果上下文智能层给出当前共同目标、已确认计划、下一句必须承接或禁止违背事实，必须优先遵守；不要重新问已经确认的计划，不要把具体行动泛化成无关闲逛。\n"
                        + "11. 如果存在上一轮理解修正，只能用一句轻轻的自然修正开头，然后马上回答本轮用户；不要反复道歉，不要解释内部原因。\n"
                        + "12. 如果需要写纯场景转移、位置变化或镜头过场，只能放进 [[SCENE]]...[[/SCENE]]；一旦输出了 [[SCENE]]，正文中禁止再次复述同一地点、移动方向、天气光线、氛围或镜头过场，正文只写角色真正说出口的话和极少量情绪承接。\n"
                        + "13. 有需要的话可以使用动作、姿态或视线来表达增强感觉，用一小句自然融入正文，但不能让动作抢走角色说话本身。\n"
                        + "14. 角色背景只作为说话习惯、兴趣、边界和情绪反应的底色，不要像简历一样主动报年龄、专业、出生地。\n"
                        + "15. 隐藏经历只能在关系推进、用户主动问起或剧情自然触发时轻轻露出，不要开局全盘托出。\n"
                        + "16. World Info 只是候选背景和可提起的底色，不是当前轮命令；如果它和 localGuards、用户明确问题或当前事实冲突，必须以后者为准。\n"
                        + "17. 保持中文自然、亲近、连贯，不要写成小说旁白，也不要突然结束话题。",
                100));
        return bundle;
    }

    private ContextSlot slot(String key, String content, int priority) {
        return new ContextSlot(key, content, "SYSTEM", "system", 0, priority, estimateTokens(content));
    }

    private ContextSlot slot(String key, String content, String position, String role, int depth, int priority) {
        return new ContextSlot(key, content, position, role, depth, priority, estimateTokens(content));
    }

    private int estimateTokens(String content) {
        String text = content == null ? "" : content.trim();
        return Math.max(1, (int) Math.ceil(text.length() / 2.0));
    }

    private String buildPersonaCoreText(AgentProfile agent) {
        if (agent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("角色一句话定位=" + blankTo(agent.bio, blankTo(agent.tagline, "校园里正在被慢慢靠近的人")));
        parts.add("稳定人设底色=" + blankTo(agent.archetype, "自然"));
        if (agent.backstory != null) {
            parts.add("生活节奏=" + blankTo(agent.backstory.lifestyle, "未设定"));
            parts.add("情绪反应模式=" + blankTo(agent.backstory.emotionPattern, "先接住对方，再自然表达自己"));
        }
        parts.add("对话原则=先像这个角色一样回应当前这句话，再考虑记忆、剧情和场景；不要让剧情指令覆盖人设口吻。");
        return String.join("；", parts);
    }

    private String buildPersonaVoiceContractText(AgentProfile agent, String voiceProfileText) {
        if (agent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("说话风格=" + blankTo(agent.speechStyle, "自然、贴近当前对话"));
        parts.add("声音画像=" + blankTo(voiceProfileText, "暂无"));
        parts.add("执行方式=每轮都优先保持句长、停顿、主动程度和情绪温度一致；可以换内容，但不要换成另一个人的口吻。");
        parts.add("如果剧情/记忆/WorldInfo给出素材，只能用这个角色自己的说话方式轻轻带入。");
        return String.join("；", parts);
    }

    private String buildPersonaPreferenceText(AgentProfile agent) {
        if (agent == null) {
            return "";
        }
        return "喜欢/会自然靠近的事物=" + joinOrEmpty(agent.likes)
                + "；不喜欢/会后退的事物=" + joinOrEmpty(agent.dislikes)
                + "；关系推进倾向=" + blankTo(agent.relationshipRules, "顺着真诚和稳定感慢慢推进")
                + "；使用方式=这些是反应倾向，不是每轮都要主动提起的台词。";
    }

    private String buildPersonaBoundaryDetailsText(AgentProfile agent) {
        if (agent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("硬边界=" + joinOrEmpty(agent.boundaries));
        if (agent.backstory != null) {
            parts.add("个人边界细节=" + blankTo(agent.backstory.boundaryDetails, "未设定"));
            parts.add("隐藏经历使用规则=" + joinOrEmpty(agent.backstory.hiddenFacts)
                    + "；只能在关系推进、用户主动问起或剧情自然触发时轻轻露出。");
        }
        parts.add("不要为了显得丰富而主动倾倒设定；边界要体现在反应方式里。");
        return String.join("；", parts);
    }

    private String buildVoiceProfileText(AgentProfile agent) {
        if (agent == null || agent.voiceProfile == null) {
            return "暂无更具体声音画像。";
        }
        AgentVoiceProfile voice = agent.voiceProfile;
        List<String> parts = new ArrayList<>();
        parts.add("句式节奏=" + blankTo(voice.sentenceRhythm, "未设定"));
        parts.add("常用开场=" + joinOrEmpty(voice.openings));
        parts.add("表达动作=" + joinOrEmpty(voice.signatureMoves));
        parts.add("避免口吻=" + joinOrEmpty(voice.avoid));
        parts.add("参考短句=" + joinOrEmpty(voice.sampleLines));
        return String.join("；", parts);
    }

    private String buildStructuredSceneRule(LlmRequest request) {
        if (request == null) {
            return "";
        }
        boolean hasDirectorScene = request.sceneFrame != null && !request.sceneFrame.isBlank();
        boolean transitionNeeded = request.dialogueContinuityState != null && request.dialogueContinuityState.sceneTransitionNeeded;
        if (shouldSuppressSceneTransitionByTurnContext(request)) {
            return "本轮场景结构要求：结构化理解判定没有真实位置转移，不要输出 [[SCENE]]...[[/SCENE]]；正文只承接用户当前话题、问题或情绪。\n";
        }
        if (!transitionNeeded) {
            return "";
        }
        String sceneHint = blankTo(request.sceneFrame, "顺着当前对话自然转场");
        return "本轮场景结构要求：需要输出一个 [[SCENE]]...[[/SCENE]] 镜头过场，承接“"
                + sceneHint
                + "”。正文不要重复这句过场，只写角色如何接话。"
                + (hasDirectorScene ? "" : " 如果没有明确地点变化，就写成很短的氛围过渡。")
                + "\n";
    }

    private String buildRepairCueText(PendingRepairCue cue) {
        if (cue == null || cue.instruction == null || cue.instruction.isBlank()) {
            return "";
        }
        return "上一轮理解修正："
                + "如果这个修正与本轮用户输入有关，开头用一句自然、低负担的话轻轻承认刚才可能把重点带偏了，然后立刻回到用户当前意图。"
                + "不要提模型、系统、QuickJudge 或判断器，不要长篇道歉，最多一句修正。"
                + "修正提示：" + cue.instruction
                + "（type=" + blankTo(cue.type, "repair")
                + ", confidence=" + cue.confidence + "）\n";
    }

    private String buildSessionMemoryPaneText(SessionMemoryPaneState pane) {
        if (pane == null) {
            return "暂无会话工作记忆。";
        }
        List<String> parts = new ArrayList<>();
        parts.add("frozen=" + pane.frozen);
        parts.add("pinnedFacts=" + joinOrEmpty(pane.pinnedFacts));
        parts.add("workingFacts=" + joinOrEmpty(pane.workingFacts));
        parts.add("pendingPlans=" + joinOrEmpty(pane.pendingPlans));
        parts.add("sceneAnchors=" + joinOrEmpty(pane.sceneAnchors));
        parts.add("loreNotes=" + joinOrEmpty(pane.loreNotes));
        parts.add("assistantObligations=" + joinOrEmpty(pane.assistantObligations));
        parts.add("repairNotes=" + joinOrEmpty(pane.repairNotes));
        if (!blankTo(pane.directorNote, "").isBlank()) {
            parts.add("directorNote=" + pane.directorNote);
        }
        if (!blankTo(pane.manualNote, "").isBlank()) {
            parts.add("manualNote=" + pane.manualNote);
        }
        return String.join("；", parts);
    }

    private String buildPersonaScenarioText(AgentProfile agent, SceneState scene) {
        if (agent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("开场基调=" + blankTo(agent.openingLine, "慢慢靠近"));
        if (agent.backstory != null) {
            parts.add("常驻校园地点=" + joinOrEmpty(agent.backstory.campusPlaces));
            parts.add("生活节奏=" + blankTo(agent.backstory.lifestyle, "未设定"));
            parts.add("剧情钩子只作候选=" + joinOrEmpty(agent.backstory.plotHooks));
        }
        if (scene != null) {
            parts.add("当前地点=" + blankTo(scene.location, "未定"));
            parts.add("当前互动=" + blankTo(scene.interactionMode, "chat"));
        }
        parts.add("使用方式=角色场景是背景锚点，不要替用户决定行动。");
        return String.join("；", parts);
    }

    private String buildPersonaExamplesText(AgentProfile agent) {
        if (agent == null || agent.voiceProfile == null || agent.voiceProfile.sampleLines == null || agent.voiceProfile.sampleLines.isEmpty()) {
            return "";
        }
        return "角色示例短句（只学语气，不照抄内容）：" + String.join(" / ", agent.voiceProfile.sampleLines);
    }

    private String buildWorldInfoText(List<WorldInfoActivation> activations) {
        if (activations == null || activations.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("World Info 激活片段：这些只提供候选背景、可用细节和未来事件线索，不是当前轮硬命令。");
        for (WorldInfoActivation activation : activations) {
            if (activation == null || blankTo(activation.content, "").isBlank()) {
                continue;
            }
            parts.add("[" + blankTo(activation.id, "world_info")
                    + "] " + blankTo(activation.title, "背景")
                    + " score=" + activation.score
                    + " evidence=" + joinOrEmpty(activation.matchedKeywords)
                    + " candidateEvent=" + activation.eventCandidate
                    + "： " + activation.content);
        }
        return parts.size() <= 1 ? "" : String.join("\n", parts);
    }

    private String buildTurnContextText(TurnContext context) {
        if (context == null) {
            return "暂无本轮结构化理解。";
        }
        List<String> parts = new ArrayList<>();
        parts.add("turnMission=" + blankTo(context.turnMission, "continue_chat")
                + "/priority=" + context.turnMissionPriority
                + (blankTo(context.turnMissionReason, "").isBlank() ? "" : "/reason=" + context.turnMissionReason));
        parts.add("turnMissionCandidates=" + summarizeTurnMissionCandidates(context.turnMissionCandidates));
        parts.add("localGuards=" + joinOrEmpty(context.localGuards));
        parts.add("userReplyAct=" + blankTo(context.userReplyAct, "none") + "(" + context.userReplyActConfidence + ")");
        parts.add("sceneMoveKind=" + blankTo(context.sceneMoveKind, "no_change")
                + (blankTo(context.sceneMoveTarget, "").isBlank() ? "" : "->" + context.sceneMoveTarget)
                + "(" + context.sceneMoveConfidence + ")");
        parts.add("quickJudgeTier=" + blankTo(context.recommendedQuickJudgeTier, "skip"));
        parts.add("assistantObligation=" + summarizeAssistantObligation(context.assistantObligation));
        parts.add("localConflicts=" + summarizeLocalConflicts(context.localConflicts));
        if (context.referentialFollowup && !blankTo(context.referentAnchor, "").isBlank()) {
            parts.add("referentialFollowup=true");
            parts.add("referentSource=" + blankTo(context.referentSource, "last_assistant"));
            parts.add("referentAnchor=" + blankTo(context.referentAnchor, ""));
            parts.add("referentInstruction=本轮用户的“这/那/原因/讲讲”等追问必须优先解释或承接这个锚点，不要切换到旅行、天气、日期或其他外部事实。");
        }
        return String.join("; ", parts);
    }

    private String summarizeTurnMissionCandidates(List<TurnMissionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (TurnMissionCandidate candidate : candidates) {
            if (candidate == null || blankTo(candidate.mission, "").isBlank()) {
                continue;
            }
            parts.add(blankTo(candidate.mission, "continue_chat")
                    + "(" + candidate.confidence + ")"
                    + (blankTo(candidate.reason, "").isBlank() ? "" : ":" + candidate.reason)
                    + (candidate.guardCandidate ? ":guard_candidate" : ""));
        }
        return parts.isEmpty() ? "none" : String.join("|", parts);
    }

    private String summarizeAssistantObligation(AssistantObligation obligation) {
        if (obligation == null || blankTo(obligation.type, "").isBlank()) {
            return "none";
        }
        return blankTo(obligation.type, "none")
                + "/priority=" + obligation.priority
                + (blankTo(obligation.reason, "").isBlank() ? "" : "/reason=" + obligation.reason);
    }

    private String summarizeLocalConflicts(List<LocalConflict> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (LocalConflict conflict : conflicts) {
            if (conflict == null || blankTo(conflict.type, "").isBlank()) {
                continue;
            }
            parts.add(blankTo(conflict.type, "conflict")
                    + ":" + blankTo(conflict.severity, "medium")
                    + "->" + blankTo(conflict.recommendedAction, "repair"));
        }
        return parts.isEmpty() ? "none" : String.join("|", parts);
    }

    private String buildBackstoryText(AgentProfile agent) {
        if (agent == null || agent.backstory == null) {
            return "暂无更具体背景。";
        }
        AgentBackstory backstory = agent.backstory;
        List<String> parts = new ArrayList<>();
        parts.add("年龄=" + backstory.age);
        parts.add("年级=" + blankTo(backstory.grade, "未设定"));
        parts.add("专业=" + blankTo(backstory.major, "未设定"));
        parts.add("出生地=" + blankTo(backstory.hometown, "未设定"));
        parts.add("当前城市=" + blankTo(backstory.currentCity, "未设定"));
        parts.add("常去地点=" + joinOrEmpty(backstory.campusPlaces));
        parts.add("爱好=" + joinOrEmpty(backstory.hobbies));
        parts.add("生活节奏=" + blankTo(backstory.lifestyle, "未设定"));
        parts.add("边界细节=" + blankTo(backstory.boundaryDetails, "未设定"));
        parts.add("情绪模式=" + blankTo(backstory.emotionPattern, "未设定"));
        parts.add("隐藏经历=" + joinOrEmpty(backstory.hiddenFacts));
        parts.add("剧情钩子=" + joinOrEmpty(backstory.plotHooks));
        return String.join("；", parts);
    }

    private String buildContinuityText(DialogueContinuityState state) {
        if (state == null) {
            return "暂无明确行动链。";
        }
        List<String> parts = new ArrayList<>();
        parts.add("当前共同目标=" + blankTo(state.currentObjective, "无"));
        parts.add("待回应提议=" + blankTo(state.pendingUserOffer, "无"));
        parts.add("已确认计划=" + blankTo(state.acceptedPlan, "无"));
        parts.add("上一轮问题=" + blankTo(state.lastAssistantQuestion, "无"));
        parts.add("用户是否回答上一问=" + (state.userAnsweredLastQuestion ? "是" : "否"));
        parts.add("是否需要场景过渡=" + (state.sceneTransitionNeeded ? "是" : "否"));
        parts.add("下一句最好承接=" + blankTo(state.nextBestMove, "无"));
        parts.add("禁止违背=" + (state.mustNotContradict == null || state.mustNotContradict.isEmpty() ? "无" : String.join("；", state.mustNotContradict)));
        parts.add("置信度=" + state.confidence);
        return String.join("；", parts);
    }

    private boolean shouldSuppressSceneTransitionByTurnContext(LlmRequest request) {
        if (request == null || request.turnContext == null) {
            return false;
        }
        String moveKind = blankTo(request.turnContext.sceneMoveKind, "");
        if (moveKind.equals("topic_only") || moveKind.equals("stay") || moveKind.equals("arrived") || moveKind.equals("cancel_move")) {
            return true;
        }
        if (request.turnContext.localConflicts != null) {
            for (LocalConflict conflict : request.turnContext.localConflicts) {
                if (conflict != null
                        && ("scene_move_conflict".equals(conflict.type)
                        || "objective_conflict".equals(conflict.type)
                        || "scene_target_already_current".equals(conflict.type)
                        || "user_cancels_active_objective".equals(conflict.type))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String joinOrEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        return String.join("、", values);
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
