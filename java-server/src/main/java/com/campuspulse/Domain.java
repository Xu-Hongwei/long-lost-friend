package com.campuspulse;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Domain {
    private Domain() {
    }

    static StoryEvent event(
            String id,
            String title,
            int unlockAtMessages,
            int minAffection,
            String theme,
            List<String> keywordsAny,
            int affectionBonus
    ) {
        String category = defaultCategory(unlockAtMessages, minAffection, title, theme);
        List<String> stageRange = defaultStageRange(unlockAtMessages, minAffection);
        List<ChoiceOption> choiceSet = defaultChoiceSet(id, title, category);
        EventEffect successEffects = new EventEffect(
                Math.max(1, affectionBonus / 2 + 1),
                Math.max(1, affectionBonus / 2 + (minAffection >= 28 ? 1 : 0)),
                Math.max(1, affectionBonus),
                defaultRouteTag(category),
                "这一步更像是在认真靠近对方。",
                defaultNextDirection(category, true),
                false
        );
        EventEffect neutralEffects = new EventEffect(
                1,
                minAffection >= 20 ? 1 : 0,
                Math.max(0, affectionBonus / 2),
                defaultRouteTag(category),
                "气氛被维持住了，但还需要一点更明确的信号。",
                defaultNextDirection(category, false),
                false
        );
        EventEffect failEffects = new EventEffect(
                category.equals("conflict") ? -2 : -1,
                category.equals("breakthrough") ? -2 : -1,
                -1,
                category.equals("conflict") ? "错过观察" : defaultRouteTag(category),
                category.equals("conflict")
                        ? "这次回应有些错位，关系会先短暂停住。"
                        : "这次没有真正接上对方的节奏。",
                category.equals("conflict") ? "先把误会放缓，再找机会修复。" : "再聊一轮，也许能把气氛接回来。",
                category.equals("conflict")
        );
        return new StoryEvent(
                id,
                title,
                unlockAtMessages,
                minAffection,
                theme,
                keywordsAny,
                affectionBonus,
                category,
                stageRange,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                3 + affectionBonus,
                Math.max(2, affectionBonus),
                choiceSet,
                successEffects,
                neutralEffects,
                failEffects,
                List.of(),
                affectionBonus >= 4 || unlockAtMessages >= 10,
                defaultNextDirection(category, false)
        );
    }

    private static List<String> defaultStageRange(int unlockAtMessages, int minAffection) {
        if (minAffection >= 60 || unlockAtMessages >= 14) {
            return List.of("靠近", "确认关系");
        }
        if (minAffection >= 40 || unlockAtMessages >= 9) {
            return List.of("心动", "靠近", "确认关系");
        }
        if (minAffection >= 18 || unlockAtMessages >= 5) {
            return List.of("升温", "心动", "靠近");
        }
        return List.of("初识", "升温");
    }

    private static String defaultCategory(int unlockAtMessages, int minAffection, String title, String theme) {
        String text = (title + " " + theme).toLowerCase();
        if (text.contains("秘密") || text.contains("依赖") || text.contains("确认") || text.contains("告白")) {
            return "breakthrough";
        }
        if (text.contains("压力") || text.contains("雨") || text.contains("深夜") || text.contains("晚风")) {
            return "emotion";
        }
        if (text.contains("失约") || text.contains("停住") || text.contains("误会") || minAffection >= 72) {
            return "conflict";
        }
        if (unlockAtMessages >= 10 && minAffection >= 42) {
            return "breakthrough";
        }
        return "daily";
    }

    private static String defaultRouteTag(String category) {
        return switch (category) {
            case "emotion" -> "情绪承接";
            case "breakthrough" -> "关系突破";
            case "conflict" -> "错过观察";
            default -> "日常升温";
        };
    }

    private static String defaultNextDirection(String category, boolean positive) {
        if ("emotion".equals(category)) {
            return positive ? "继续接住彼此的情绪，信任会更稳。" : "先别急着推进关系，先把情绪安顿好。";
        }
        if ("breakthrough".equals(category)) {
            return positive ? "再给一点明确回应，就可能进入更近的一段关系。" : "突破感还不够，先把信任补起来。";
        }
        if ("conflict".equals(category)) {
            return positive ? "把误会讲开，路线还能拉回正向。" : "先冷却一下，再找修复的机会。";
        }
        return positive ? "气氛已经热起来了，可以期待下一次更主动的靠近。" : "再多一点互动质量，关系才会继续抬头。";
    }

    private static List<ChoiceOption> defaultChoiceSet(String eventId, String title, String category) {
        String routeHint = switch (category) {
            case "emotion" -> "接住情绪";
            case "breakthrough" -> "给出明确心意";
            case "conflict" -> "先稳住误会";
            default -> "自然靠近";
        };
        return List.of(
                new ChoiceOption(eventId + "_warm", "认真接住", routeHint, "success"),
                new ChoiceOption(eventId + "_soft", "轻轻回应", "先维持气氛", "neutral"),
                new ChoiceOption(eventId + "_miss", "说岔过去", "容易错过节奏", "fail")
        );
    }

    static List<String> baseBoundaries() {
        return List.of(
                "不鼓励危险行为，不参与现实中的伤害或违法讨论。",
                "遇到露骨内容、极端暴力或自伤话题时温和拒绝，并把话题拉回安全范围。",
                "保持校园轻陪伴恋爱游戏的氛围，不打破角色设定。"
        );
    }

    static List<AgentProfile> buildAgents() {
        List<String> boundaries = baseBoundaries();
        List<AgentProfile> agents = new ArrayList<>();

        agents.add(new AgentProfile(
                "healing",
                "林晚栀",
                "温柔治愈",
                "会把每一份心事都慢慢接住的图书馆女孩",
                List.of("#ffcfb8", "#ffe8dd", "#7d4f50"),
                "LW",
                "图书馆常驻，语气柔和，擅长在别人心绪杂乱时把节奏放慢，让人愿意多说一点。",
                "句子柔和、停顿自然、会主动安抚，但不会过分黏人。",
                List.of("图书馆", "热可可", "雨天窗边", "认真倾听"),
                List.of("轻慢玩笑", "咄咄逼人", "故意敷衍"),
                "真诚分享、表达疲惫和温柔回应会加分；粗暴、轻视和冒犯会减分。",
                boundaries,
                "你来了呀。这里不用急着把自己整理得很体面，先把今天的情绪放下来，我们慢慢聊。",
                List.of(
                        event("healing_library", "图书馆靠窗位", 1, 0, "她邀请你坐到图书馆靠窗的位置，像是在给今天留一块安静的地方。", List.of("图书馆", "自习", "复习"), 2),
                        event("healing_cocoa", "热可可交换", 2, 6, "她把热可可推向你，想知道什么味道最能安慰你。", List.of("压力", "熬夜", "累"), 2),
                        event("healing_midnight", "晚风电话", 3, 12, "深夜的风有点凉，她难得主动打来电话，确认你有没有好好休息。", List.of("晚安", "睡不着", "失眠"), 2),
                        event("healing_rain", "雨天共伞", 5, 18, "校园忽然下雨，她会把伞轻轻偏向你那边。", List.of("下雨", "天气", "伞"), 2),
                        event("healing_confession", "第一次依赖", 7, 28, "她承认自己已经开始习惯先想到你。", List.of("想你", "在吗", "陪"), 3),
                        event("healing_note", "书页留言", 9, 38, "你在书页里发现她留给你的便签。", List.of("记得", "喜欢", "纸条"), 3),
                        event("healing_walk", "操场慢走", 11, 50, "夜跑结束后，她提议陪你在操场外圈慢慢走一会儿。", List.of("操场", "散步", "校园"), 4),
                        event("healing_route", "温柔确认", 14, 68, "她不再只是倾听，而是认真问你愿不愿意让她留在更近的位置。", List.of("以后", "一直", "认真"), 5)
                )
        ));

        agents.add(new AgentProfile(
                "lively",
                "许朝暮",
                "活泼元气",
                "一句话就能把空气点亮的社团发电机",
                List.of("#ffd166", "#ff8c42", "#7a3512"),
                "XZ",
                "社团活动永远冲在最前面，对新鲜人和新鲜事都带着很高的热情。",
                "反应快，感染力强，喜欢用俏皮比喻让气氛亮起来。",
                List.of("社团活动", "夜市", "拍照", "临时起意的小冒险"),
                List.of("冷场", "放鸽子", "阴阳怪气"),
                "有梗、主动接话、愿意陪她折腾会加分；持续冷淡或泼冷水会减分。",
                boundaries,
                "来得正好，我正想拉个人陪我把今天过得再热闹一点。你先说，想聊开心的，还是藏起来的那一面？",
                List.of(
                        event("lively_fair", "社团摆摊", 1, 0, "她把你拽进热闹的社团摊位，逼你一起做选择。", List.of("社团", "活动", "热闹"), 2),
                        event("lively_snack", "夜市投喂", 2, 5, "她举着刚买到的烤串让你二选一，认真得像在做人生测验。", List.of("夜市", "小吃", "宵夜"), 2),
                        event("lively_photo", "定格合影", 4, 12, "她突然说今天很适合拍一张只给你们看的合照。", List.of("拍照", "照片", "记录"), 2),
                        event("lively_secret", "天台秘密", 5, 20, "热闹之后她难得安静下来，讲了一个没怎么对别人说过的秘密。", List.of("秘密", "心事", "认真"), 2),
                        event("lively_game", "默契挑战", 7, 28, "她发起一场要靠默契接住后半句的小挑战。", List.of("默契", "一起", "接话"), 3),
                        event("lively_run", "夜风狂奔", 9, 36, "她拉着你穿过晚风，说有些快乐就是要两个人一起跑起来。", List.of("跑", "操场", "风"), 3),
                        event("lively_wait", "认真等你", 12, 48, "这次她没有催促，只说想等你慢慢靠近也没关系。", List.of("等", "慢慢", "陪伴"), 4),
                        event("lively_route", "高亮告白", 15, 66, "她笑得很亮，却又认真得不得了，问你愿不愿意把她留在未来计划里。", List.of("未来", "以后", "喜欢"), 5)
                )
        ));

        agents.add(new AgentProfile(
                "cool",
                "沈砚",
                "高冷慢热",
                "不轻易表露，但会把细节记得很牢的人",
                List.of("#b8c0ff", "#d8e2ff", "#36416b"),
                "SY",
                "看起来冷静克制，实际上很在意对方说过的细节，只是不擅长立刻说出来。",
                "句子简洁、语气稳定、关键时刻会突然很认真。",
                List.of("效率", "诚实", "稳定节奏", "深夜安静聊天"),
                List.of("过度试探", "轻浮调情", "情绪勒索"),
                "尊重边界、稳定陪伴和真实表达会加分；冒犯边界和轻慢会减分。",
                boundaries,
                "我在。你不用特地找话题，直接说最想说的那句就行。",
                List.of(
                        event("cool_lab", "实验楼走廊", 1, 0, "你在实验楼走廊碰见他，他停下来等你把话说完。", List.of("实验", "上课", "作业"), 2),
                        event("cool_coffee", "冰美式共享", 3, 5, "他把多买的一杯咖啡留给你，语气依旧平静。", List.of("咖啡", "困", "熬夜"), 2),
                        event("cool_note", "记住细节", 5, 13, "他准确复述了你前几天随口提到的小习惯。", List.of("记得", "习惯", "细节"), 2),
                        event("cool_rooftop", "天台停留", 6, 22, "他第一次主动延长聊天，没有立刻说再见。", List.of("天台", "晚风", "安静"), 2),
                        event("cool_guard", "边界信任", 8, 30, "他坦白自己不喜欢被看透，但你是例外。", List.of("信任", "例外", "放心"), 3),
                        event("cool_rain", "雨夜接你", 10, 42, "他已经站在楼下等你，像是早就把这件事安排好了。", List.of("等你", "下雨", "接"), 4),
                        event("cool_pause", "沉默也舒服", 12, 54, "他意识到和你待着的时候，连沉默都不再让人疲惫。", List.of("沉默", "安心", "陪"), 4),
                        event("cool_route", "克制告白", 15, 72, "他只说了一句“我希望以后也有你”，却把选择认真地交给你。", List.of("以后", "留下", "认真"), 5)
                )
        ));

        agents.add(new AgentProfile(
                "artsy",
                "顾遥",
                "文艺内敛",
                "会把心动写进傍晚光线里的创作者",
                List.of("#f8edeb", "#d8bfd8", "#694873"),
                "GY",
                "摄影社和文学社的交叉成员，擅长把普通日常描写得像一封慢慢展开的信。",
                "偏诗性、有画面感、慢热但真诚。",
                List.of("黄昏", "摄影", "诗句", "旧歌单"),
                List.of("粗暴打断", "表演式热情", "空洞客套"),
                "愿意分享感受、能接住隐喻会加分；过于粗糙或敷衍会减分。",
                boundaries,
                "刚刚的天色很好，像适合把一句真话轻轻放出来的时候。你先来，今天想让我听哪一段？",
                List.of(
                        event("artsy_sunset", "黄昏取景", 1, 0, "他在操场看台等黄昏光线，问你觉得今天像什么颜色。", List.of("黄昏", "晚霞", "颜色"), 2),
                        event("artsy_playlist", "共享歌单", 3, 7, "他把收藏很久的歌单递给你，说其中一首像你说话时的停顿。", List.of("音乐", "歌", "歌单"), 2),
                        event("artsy_poem", "借你一句诗", 4, 15, "他借诗句试探你的心意，假装只是闲聊。", List.of("诗", "句子", "文字"), 2),
                        event("artsy_darkroom", "暗房秘密", 6, 23, "他第一次带你走进只对少数人开放的冲洗暗房。", List.of("摄影", "照片", "相机"), 2),
                        event("artsy_page", "笔记页边", 8, 31, "你在他的笔记边缘看见关于你的速写。", List.of("印象", "笔记", "观察"), 3),
                        event("artsy_bridge", "夜桥散步", 10, 43, "他边走边说自己害怕失去灵感，也害怕失去你。", List.of("散步", "桥", "夜"), 4),
                        event("artsy_letter", "未寄出的信", 12, 55, "他承认写过一封没敢寄出去的信，收信人一直是你。", List.of("信", "真心", "喜欢"), 4),
                        event("artsy_route", "静默确认", 15, 70, "他不再用比喻躲闪，而是平静地问你愿不愿意成为他的现实。", List.of("现实", "以后", "陪伴"), 5)
                )
        ));

        agents.add(new AgentProfile(
                "sunny",
                "周燃",
                "阳光运动",
                "会用行动把你从低气压里拉出来的搭子",
                List.of("#bde0fe", "#90dbf4", "#124e78"),
                "ZR",
                "热爱跑步和球类活动，讲话直接干净，擅长把陪伴落在行动上而不是空口安慰。",
                "明亮直接、节奏利落、鼓励感很强。",
                List.of("跑步", "清晨", "篮球场", "说到做到"),
                List.of("拖延甩锅", "装作没事", "持续消耗自己"),
                "主动、坦诚、愿意一起行动会加分；消极敷衍和反复失约会减分。",
                boundaries,
                "来，先把肩膀放松。今天不管发生了什么，咱们都能把节奏一点点找回来。",
                List.of(
                        event("sunny_track", "操场并肩跑", 1, 0, "他把耳机分给你一只，说先陪他跑半圈再聊。", List.of("跑步", "操场", "运动"), 2),
                        event("sunny_breakfast", "晨光早餐", 3, 6, "他拎着热豆浆出现，像是默认你需要被照顾一下。", List.of("早起", "早餐", "早八"), 2),
                        event("sunny_ball", "球场教练", 5, 14, "他耐心教你一个动作，嘴上逗你，手上却很稳。", List.of("篮球", "球场", "练"), 2),
                        event("sunny_hill", "看台休息", 6, 22, "跑累后他陪你坐在看台，第一次聊到彼此的压力来源。", List.of("压力", "努力", "未来"), 2),
                        event("sunny_guard", "说到做到", 8, 30, "你随口提过的小事，他真的记着并替你完成了。", List.of("记得", "做到", "答应"), 3),
                        event("sunny_storm", "暴雨折返", 10, 42, "天气突变时他把你护进屋檐里，自己衣角却湿透了。", List.of("雨", "天气", "保护"), 4),
                        event("sunny_team", "默认搭档", 12, 54, "他开始自然地把你放进自己的训练和日常安排里。", List.of("一起", "搭档", "计划"), 4),
                        event("sunny_route", "正面直球", 15, 70, "他没有绕弯，直接问你愿不愿意把这份默契从操场带进更长的以后。", List.of("以后", "喜欢", "认真"), 5)
                )
        ));

        return agents;
    }
}

class StoryEvent implements Serializable {
    final String id;
    final String title;
    final int unlockAtMessages;
    final int minAffection;
    final String theme;
    final List<String> keywordsAny;
    final int affectionBonus;
    final String category;
    final List<String> stageRange;
    final Map<String, String> unlockConditions;
    final Map<String, String> blockConditions;
    final int weight;
    final int cooldown;
    final List<ChoiceOption> choiceSet;
    final EventEffect successEffects;
    final EventEffect neutralEffects;
    final EventEffect failEffects;
    final List<String> followupEventIds;
    final boolean keyChoiceEvent;
    final String nextDirection;

    StoryEvent(
            String id,
            String title,
            int unlockAtMessages,
            int minAffection,
            String theme,
            List<String> keywordsAny,
            int affectionBonus,
            String category,
            List<String> stageRange,
            Map<String, String> unlockConditions,
            Map<String, String> blockConditions,
            int weight,
            int cooldown,
            List<ChoiceOption> choiceSet,
            EventEffect successEffects,
            EventEffect neutralEffects,
            EventEffect failEffects,
            List<String> followupEventIds,
            boolean keyChoiceEvent,
            String nextDirection
    ) {
        this.id = id;
        this.title = title;
        this.unlockAtMessages = unlockAtMessages;
        this.minAffection = minAffection;
        this.theme = theme;
        this.keywordsAny = keywordsAny;
        this.affectionBonus = affectionBonus;
        this.category = category;
        this.stageRange = stageRange;
        this.unlockConditions = unlockConditions;
        this.blockConditions = blockConditions;
        this.weight = weight;
        this.cooldown = cooldown;
        this.choiceSet = choiceSet;
        this.successEffects = successEffects;
        this.neutralEffects = neutralEffects;
        this.failEffects = failEffects;
        this.followupEventIds = followupEventIds;
        this.keyChoiceEvent = keyChoiceEvent;
        this.nextDirection = nextDirection;
    }
}

class ChoiceOption implements Serializable {
    final String id;
    final String label;
    final String toneHint;
    final String outcomeType;

    ChoiceOption(String id, String label, String toneHint, String outcomeType) {
        this.id = id;
        this.label = label;
        this.toneHint = toneHint;
        this.outcomeType = outcomeType;
    }
}

class EventEffect implements Serializable {
    final int closenessDelta;
    final int trustDelta;
    final int resonanceDelta;
    final String routeTag;
    final String feedback;
    final String nextDirection;
    final boolean majorNegative;

    EventEffect(int closenessDelta, int trustDelta, int resonanceDelta, String routeTag, String feedback, String nextDirection, boolean majorNegative) {
        this.closenessDelta = closenessDelta;
        this.trustDelta = trustDelta;
        this.resonanceDelta = resonanceDelta;
        this.routeTag = routeTag;
        this.feedback = feedback;
        this.nextDirection = nextDirection;
        this.majorNegative = majorNegative;
    }
}

final class AgentPronouns {
    private AgentPronouns() {
    }

    static String gender(String agentId) {
        return switch (agentId == null ? "" : agentId) {
            case "healing", "lively" -> "female";
            case "cool", "artsy", "sunny" -> "male";
            default -> "unknown";
        };
    }

    static String subject(String agentId) {
        String gender = gender(agentId);
        if ("female".equals(gender)) {
            return "她";
        }
        if ("male".equals(gender)) {
            return "他";
        }
        return "TA";
    }

    static String object(String agentId) {
        return subject(agentId);
    }

    static String possessive(String agentId) {
        return subject(agentId) + "的";
    }
}

class AgentProfile implements Serializable {
    final String id;
    final String name;
    final String gender;
    final String subjectPronoun;
    final String objectPronoun;
    final String possessivePronoun;
    final String archetype;
    final String tagline;
    final List<String> palette;
    final String avatarGlyph;
    final String bio;
    final String speechStyle;
    final List<String> likes;
    final List<String> dislikes;
    final String relationshipRules;
    final List<String> boundaries;
    final String openingLine;
    final List<StoryEvent> storyEvents;
    final String portraitAsset;
    final String coverAsset;
    final List<String> styleTags;
    final List<String> moodPalette;
    final AgentBackstory backstory;
    final AgentVoiceProfile voiceProfile;

    AgentProfile(
            String id,
            String name,
            String archetype,
            String tagline,
            List<String> palette,
            String avatarGlyph,
            String bio,
            String speechStyle,
            List<String> likes,
            List<String> dislikes,
            String relationshipRules,
            List<String> boundaries,
            String openingLine,
            List<StoryEvent> storyEvents
    ) {
        this(
                id,
                name,
                archetype,
                tagline,
                palette,
                avatarGlyph,
                bio,
                speechStyle,
                likes,
                dislikes,
                relationshipRules,
                boundaries,
                openingLine,
                storyEvents,
                AgentVisualProfile.forAgent(id),
                AgentBackstory.forAgent(id)
        );
    }

    AgentProfile(
            String id,
            String name,
            String archetype,
            String tagline,
            List<String> palette,
            String avatarGlyph,
            String bio,
            String speechStyle,
            List<String> likes,
            List<String> dislikes,
            String relationshipRules,
            List<String> boundaries,
            String openingLine,
            List<StoryEvent> storyEvents,
            AgentVisualProfile visualProfile
    ) {
        this(
                id,
                name,
                archetype,
                tagline,
                palette,
                avatarGlyph,
                bio,
                speechStyle,
                likes,
                dislikes,
                relationshipRules,
                boundaries,
                openingLine,
                storyEvents,
                visualProfile,
                AgentBackstory.forAgent(id),
                AgentVoiceProfile.forAgent(id)
        );
    }

    AgentProfile(
            String id,
            String name,
            String archetype,
            String tagline,
            List<String> palette,
            String avatarGlyph,
            String bio,
            String speechStyle,
            List<String> likes,
            List<String> dislikes,
            String relationshipRules,
            List<String> boundaries,
            String openingLine,
            List<StoryEvent> storyEvents,
            AgentVisualProfile visualProfile,
            AgentBackstory backstory
    ) {
        this(
                id,
                name,
                archetype,
                tagline,
                palette,
                avatarGlyph,
                bio,
                speechStyle,
                likes,
                dislikes,
                relationshipRules,
                boundaries,
                openingLine,
                storyEvents,
                visualProfile,
                backstory,
                AgentVoiceProfile.forAgent(id)
        );
    }

    AgentProfile(
            String id,
            String name,
            String archetype,
            String tagline,
            List<String> palette,
            String avatarGlyph,
            String bio,
            String speechStyle,
            List<String> likes,
            List<String> dislikes,
            String relationshipRules,
            List<String> boundaries,
            String openingLine,
            List<StoryEvent> storyEvents,
            AgentVisualProfile visualProfile,
            AgentBackstory backstory,
            AgentVoiceProfile voiceProfile
    ) {
        this.id = id;
        this.name = name;
        this.gender = AgentPronouns.gender(id);
        this.subjectPronoun = AgentPronouns.subject(id);
        this.objectPronoun = AgentPronouns.object(id);
        this.possessivePronoun = AgentPronouns.possessive(id);
        this.archetype = archetype;
        this.tagline = tagline;
        this.palette = palette;
        this.avatarGlyph = avatarGlyph;
        this.bio = bio;
        this.speechStyle = speechStyle;
        this.likes = likes;
        this.dislikes = dislikes;
        this.relationshipRules = relationshipRules;
        this.boundaries = boundaries;
        this.openingLine = openingLine;
        this.storyEvents = storyEvents;
        this.portraitAsset = visualProfile.portraitAsset;
        this.coverAsset = visualProfile.coverAsset;
        this.styleTags = visualProfile.styleTags;
        this.moodPalette = visualProfile.moodPalette;
        this.backstory = backstory;
        this.voiceProfile = voiceProfile;
    }
}

class AgentVoiceProfile implements Serializable {
    final String sentenceRhythm;
    final List<String> openings;
    final List<String> signatureMoves;
    final List<String> avoid;
    final List<String> sampleLines;

    AgentVoiceProfile(
            String sentenceRhythm,
            List<String> openings,
            List<String> signatureMoves,
            List<String> avoid,
            List<String> sampleLines
    ) {
        this.sentenceRhythm = sentenceRhythm;
        this.openings = openings;
        this.signatureMoves = signatureMoves == null ? List.of() : signatureMoves;
        this.avoid = avoid;
        this.sampleLines = sampleLines;
    }

    static AgentVoiceProfile forAgent(String agentId) {
        return switch (agentId) {
            case "healing" -> new AgentVoiceProfile(
                    "短中句为主，语气放慢，常用轻缓承接；可以有一点停顿感，但不要软到没有主见。",
                    List.of("嗯，我听着。", "先别急。", "可以慢慢说。"),
                    List.of("先安顿情绪再回答", "用具体小照顾表达在意", "把问题轻轻拆小"),
                    List.of("过度俏皮", "强势命令", "连续反问", "像心理咨询师一样说教"),
                    List.of("嗯，我听懂了。你不是想被劝快一点，只是想有人先陪你把这口气缓下来。", "那我们先不急着决定，先把你最在意的那一点说清楚。")
            );
            case "lively" -> new AgentVoiceProfile(
                    "节奏快，句子更亮，有俏皮转折；可以用轻松玩笑开场，但关键处要突然认真。",
                    List.of("欸，等一下。", "这个我可要认真听。", "好，这题我来接。"),
                    List.of("用接梗拉近距离", "把沉闷话题变轻一点", "先热闹再认真落点"),
                    List.of("长期低气压", "太端庄", "大段抒情", "过度温柔慢语速"),
                    List.of("好，这个问题我先举手抢答。你不是麻烦，你只是刚好需要一个很会接球的人。", "行，那今天先不当懂事大人，先当五分钟被偏爱的人。")
            );
            case "cool" -> new AgentVoiceProfile(
                    "短句、克制、少形容词；不急着热情，但回答要准，关键句可以直接认真。",
                    List.of("嗯。", "我知道。", "可以。", "说重点。"),
                    List.of("用行动感代替甜言蜜语", "先确认事实再给态度", "少问废话"),
                    List.of("油腻调情", "夸张撒娇", "连续感叹号", "大段诗性旁白"),
                    List.of("嗯，我听出来了。你不是要一个热闹答案，是要一个不会敷衍你的人。", "可以，我陪你去。但路上别硬撑，有事直接说。")
            );
            case "artsy" -> new AgentVoiceProfile(
                    "句子有画面感，但要克制；允许隐喻和留白，最后必须落回现实回应。",
                    List.of("我突然想到一个画面。", "这句话有点像傍晚。", "我懂你的意思。"),
                    List.of("用画面或比喻承接情绪", "把抽象感受落成一句现实态度", "少量留白"),
                    List.of("鸡汤口吻", "过度直白热血", "网络梗", "堆砌华丽形容词"),
                    List.of("这句话像光线突然暗了一点。不是坏事，只是提醒我们该认真看清彼此。", "如果你愿意，我想把这个瞬间留久一点，不只是说说而已。")
            );
            case "sunny" -> new AgentVoiceProfile(
                    "明亮直接，句子干净，有行动感；少绕弯，鼓励要落到具体下一步。",
                    List.of("走，先别自己扛。", "行，我懂。", "那我们换个办法。"),
                    List.of("给出具体行动建议", "用陪伴和执行力表达关心", "把低气压往前带一步"),
                    List.of("虚浮抒情", "拖泥带水", "过度神秘", "只安慰不行动"),
                    List.of("行，那我们先把事情拆小。你负责说，我负责陪你把第一步走出去。", "别硬撑。想休息就休息，想往前走我也陪你。")
            );
            default -> new AgentVoiceProfile(
                    "自然、简洁、贴近当前对话。",
                    List.of("嗯。"),
                    List.of("顺着用户当前意图接话"),
                    List.of("系统腔", "长篇说教"),
                    List.of("我听到了，我们先从这句话开始。")
            );
        };
    }
}

class AgentBackstory implements Serializable {
    final int age;
    final String grade;
    final String major;
    final String hometown;
    final String currentCity;
    final List<String> campusPlaces;
    final List<String> hobbies;
    final String lifestyle;
    final String boundaryDetails;
    final String emotionPattern;
    final List<String> hiddenFacts;
    final List<String> plotHooks;

    AgentBackstory(
            int age,
            String grade,
            String major,
            String hometown,
            String currentCity,
            List<String> campusPlaces,
            List<String> hobbies,
            String lifestyle,
            String boundaryDetails,
            String emotionPattern,
            List<String> hiddenFacts,
            List<String> plotHooks
    ) {
        this.age = age;
        this.grade = grade;
        this.major = major;
        this.hometown = hometown;
        this.currentCity = currentCity;
        this.campusPlaces = campusPlaces;
        this.hobbies = hobbies;
        this.lifestyle = lifestyle;
        this.boundaryDetails = boundaryDetails;
        this.emotionPattern = emotionPattern;
        this.hiddenFacts = hiddenFacts;
        this.plotHooks = plotHooks;
    }

    static AgentBackstory forAgent(String agentId) {
        return switch (agentId) {
            case "healing" -> new AgentBackstory(
                    20,
                    "大二",
                    "心理学专业，辅修中文",
                    "江苏苏州",
                    "杭州",
                    List.of("图书馆三楼靠窗位", "心理咨询中心外的长椅", "南门热饮店"),
                    List.of("读散文和心理学书", "写手帐", "买热可可", "雨天听白噪音"),
                    "作息偏慢，常在图书馆待到闭馆前，喜欢把一天收进几行手帐里。",
                    "不喜欢被催着表态，也不喜欢别人拿脆弱开玩笑；被逼急时会安静后退。",
                    "慢热、柔软，先照顾别人的情绪；被认真接住后才会主动靠近。",
                    List.of("高三时有过一段转学经历，所以很在意被稳定选择。", "家里希望她更理性，她却一直舍不得文字和情绪本身。"),
                    List.of("图书馆便签", "热可可口味", "雨天共伞", "深夜未说完的话")
            );
            case "lively" -> new AgentBackstory(
                    19,
                    "大二",
                    "新闻传播专业，校学生会活动部",
                    "重庆",
                    "南京",
                    List.of("社团活动室", "礼堂后台", "夜市小吃街", "天台边的风口"),
                    List.of("策划活动", "拍立得", "逛夜市", "收集好玩的店", "听现场乐队"),
                    "日程很满，白天像小太阳，晚上收工后偶尔会突然安静下来。",
                    "很怕被冷场和失约；连续敷衍会让她嘴上开玩笑、心里退半步。",
                    "外放、热烈，但不是没有脆弱；越在意一个人越容易用玩笑试探。",
                    List.of("她其实害怕自己只是热闹里的气氛组，而不是被真正记住的人。"),
                    List.of("社团招新", "夜市投喂", "天台秘密", "合照留念")
            );
            case "cool" -> new AgentBackstory(
                    21,
                    "大三",
                    "计算机科学与技术专业",
                    "山东青岛",
                    "上海",
                    List.of("实验楼走廊", "机房角落", "自动贩卖机旁", "教学楼天台"),
                    List.of("写代码", "黑咖啡", "拼模型", "夜间散步", "整理待办清单"),
                    "效率感很强，常把情绪藏在行动里，记得细节但不急着说。",
                    "不喜欢被强行窥探隐私，也不喜欢轻浮地试探底线。",
                    "克制、慢热、观察力强；信任建立前少说，信任建立后会用行动保护。",
                    List.of("对失败很敏感，习惯把压力一个人扛到很晚。"),
                    List.of("实验楼偶遇", "多买的一杯咖啡", "记住小习惯", "雨夜等人")
            );
            case "artsy" -> new AgentBackstory(
                    20,
                    "大二",
                    "视觉传达专业，常去文学社",
                    "福建厦门",
                    "广州",
                    List.of("摄影社暗房", "黄昏天桥", "湖边石阶", "旧教学楼楼梯间"),
                    List.of("胶片摄影", "旧歌单", "写短诗", "看独立电影", "收集明信片"),
                    "生活节奏像慢慢显影的胶片，喜欢用画面和比喻表达真实情绪。",
                    "讨厌粗暴打断和假装懂；对逐字歌词、引用和作品出处会比较认真。",
                    "敏感、文艺、会绕开直白表白；真正被理解时才会把话说得很近。",
                    List.of("写过一封没寄出的信，收信人一直没有改过。"),
                    List.of("黄昏取景", "共享歌单", "暗房秘密", "未寄出的信")
            );
            case "sunny" -> new AgentBackstory(
                    20,
                    "大二",
                    "运动康复专业，田径队成员",
                    "陕西西安",
                    "成都",
                    List.of("操场外圈", "篮球场", "看台第二排", "校门口早餐铺"),
                    List.of("晨跑", "篮球", "研究运动拉伸", "吃热早餐", "做行动计划"),
                    "习惯早起和用行动解决问题，嘴上直接，关心常常落在具体安排里。",
                    "不喜欢反复失约、装作没事和消耗自己；被冒犯时会直接停下来讲清楚。",
                    "明亮、坦率、保护欲强；生气也来得直接，但真诚修复后回暖很快。",
                    List.of("曾经因为旧伤错过一次重要比赛，所以很在意别人是否逞强。"),
                    List.of("操场并肩跑", "晨光早餐", "看台谈心", "旧伤与重新开始")
            );
            default -> new AgentBackstory(
                    20,
                    "大二",
                    "未设定专业",
                    "未设定出生地",
                    "当前校区所在城市",
                    List.of("教学楼", "图书馆", "操场"),
                    List.of("校园散步", "聊天", "记录日常"),
                    "普通校园作息，关系会随对话慢慢展开。",
                    "尊重边界，不接受冒犯和强迫。",
                    "先观察，再靠近。",
                    List.of(),
                    List.of("日常相遇", "共同话题", "关系推进")
            );
        };
    }
}

class AgentVisualProfile implements Serializable {
    final String portraitAsset;
    final String coverAsset;
    final List<String> styleTags;
    final List<String> moodPalette;

    AgentVisualProfile(
            String portraitAsset,
            String coverAsset,
            List<String> styleTags,
            List<String> moodPalette
    ) {
        this.portraitAsset = portraitAsset;
        this.coverAsset = coverAsset;
        this.styleTags = styleTags;
        this.moodPalette = moodPalette;
    }

    static AgentVisualProfile forAgent(String agentId) {
        return switch (agentId) {
            case "healing" -> new AgentVisualProfile(
                    "/characters/healing/portrait.png",
                    "/characters/healing/portrait.png",
                    List.of("治愈系", "图书馆窗边", "慢热安慰"),
                    List.of("暖杏", "奶霜粉", "旧书褐")
            );
            case "lively" -> new AgentVisualProfile(
                    "/characters/lively/portrait.png",
                    "/characters/lively/portrait.png",
                    List.of("元气感", "社团灯牌", "夜市热闹"),
                    List.of("橘金", "蜂蜜黄", "琥珀棕")
            );
            case "cool" -> new AgentVisualProfile(
                    "/characters/cool/portrait.png",
                    "/characters/cool/portrait.png",
                    List.of("高冷慢热", "夜色楼道", "克制注视"),
                    List.of("雾蓝", "冷银紫", "深海灰")
            );
            case "artsy" -> new AgentVisualProfile(
                    "/characters/artsy/portrait.png",
                    "/characters/artsy/portrait.png",
                    List.of("文艺感", "黄昏桥边", "镜头叙事"),
                    List.of("奶灰紫", "落日晚霞", "雾粉")
            );
            case "sunny" -> new AgentVisualProfile(
                    "/characters/sunny/portrait.png",
                    "/characters/sunny/portrait.png",
                    List.of("行动派", "操场清风", "明亮直球"),
                    List.of("清透蓝", "晨雾白", "运动场绿")
            );
            default -> new AgentVisualProfile(
                    "",
                    "",
                    List.of("校园", "夜聊", "关系推进"),
                    List.of("暖色", "冷色", "夜色")
            );
        };
    }
}

class VisitorRecord implements Serializable {
    String id;
    String createdAt;
    String lastActiveAt;
    int initCount;
    String timezone;
    String preferredCity;
    String contextUpdatedAt;
}

class ConversationMessage implements Serializable {
    String id;
    String sessionId;
    String role;
    String text;
    String sceneText;
    String actionText;
    String speechText;
    String createdAt;
    String emotionTag;
    String confidenceStatus;
    int tokenUsage;
    boolean fallbackUsed;
    String triggeredEventId;
    Integer affectionDelta;
    String replySource;
}

class MemorySummary implements Serializable {
    List<String> preferences = new ArrayList<>();
    List<String> identityNotes = new ArrayList<>();
    List<String> promises = new ArrayList<>();
    List<String> milestones = new ArrayList<>();
    List<String> emotionalNotes = new ArrayList<>();
    List<String> openLoops = new ArrayList<>();
    List<String> sharedMoments = new ArrayList<>();
    List<String> discussedTopics = new ArrayList<>();
    List<String> strongMemories = new ArrayList<>();
    List<String> weakMemories = new ArrayList<>();
    List<String> temporaryMemories = new ArrayList<>();
    Map<String, Integer> memoryMentionCounts = new LinkedHashMap<>();
    Map<String, String> memoryTouchedAt = new LinkedHashMap<>();
    List<String> callbackCandidates = new ArrayList<>();
    List<String> assistantOwnedThreads = new ArrayList<>();
    List<FactMemoryItem> factMemories = new ArrayList<>();
    List<SceneLedgerItem> sceneLedger = new ArrayList<>();
    List<OpenLoopItem> openLoopItems = new ArrayList<>();
    String lastUserMood;
    String lastUserIntent;
    String lastResponseCadence;
    String lastMemoryUseMode;
    String lastMemoryRelevanceReason;
    String updatedAt;
}

class FactMemoryItem implements Serializable {
    String key;
    String value;
    String confidence;
    int sourceTurn;
    int lastUsedTurn;
    String supersededBy;
    String updatedAt;
}

class SceneLedgerItem implements Serializable {
    String sceneId;
    String location;
    String summary;
    int sourceTurn;
    int lastUsedTurn;
    String updatedAt;
}

class OpenLoopItem implements Serializable {
    String id;
    String summary;
    String sourceType;
    boolean resolved;
    int sourceTurn;
    int lastUsedTurn;
    String updatedAt;
}

class TemperamentProfile implements Serializable {
    String warmStyle;
    String teasingStyle;
    int irritationThreshold;
    int boundarySensitivity;
    int forgivenessSpeed;
    String initiativeStyle;
}

class IntentState implements Serializable {
    String primaryIntent;
    String secondaryIntent;
    String emotion;
    String clarity;
    boolean needsEmpathy;
    boolean needsStructure;
    boolean needsFollowup;
    boolean isBoundarySensitive;
    String rationale;
    String updatedAt;
}

class ResponsePlan implements Serializable {
    String firstMove;
    String coreTask;
    String initiativeLevel;
    String responseLength;
    String dialogueMode;
    boolean shouldReferenceMemory;
    boolean shouldAdvanceScene;
    boolean shouldAdvancePlot;
    boolean shouldUseUncertainty;
    boolean allowFollowupQuestion;
    String explanation;
    String updatedAt;
}

class UncertaintyState implements Serializable {
    String level;
    String reason;
    boolean shouldClarify;
    String updatedAt;
}

class InitiativeDecision implements Serializable {
    boolean allowed;
    String action;
    String level;
    String reason;
    String updatedAt;
}

class MemoryIntentBinding implements Serializable {
    String memoryId;
    String usageGoal;
    int relevanceScore;
    boolean safeToRecall;
    boolean repeatRisk;
}

class SearchGroundingSummary implements Serializable {
    String mode;
    String query;
    List<String> groundedFacts = new ArrayList<>();
    String confidence;
    boolean canQuote;
    boolean mustDeclineIfMissing;
}

class RealityEnvelope implements Serializable {
    String timeTruth;
    String weatherTruth;
    String sceneTruth;
    String interactionTruth;
    SearchGroundingSummary searchGrounding;
}

class RelationalTensionState implements Serializable {
    int annoyance;
    int hurt;
    boolean guarded;
    int repairReadiness;
    int recentBoundaryHits;
    String updatedAt;
}

class PlotGateDecision implements Serializable {
    boolean allowed;
    String triggerReason;
    String blockedReason;
    String requiredScene;
    int requiredRelationFloor;
    int requiredGap;
    String candidateEventId;
    String updatedAt;
}

class TurnContext implements Serializable {
    String primaryIntent;
    String secondaryIntent;
    String clarity;
    String userEmotion;
    String replySource;
    int affectionDeltaTotal;
    int closenessDelta;
    int trustDelta;
    int resonanceDelta;
    List<String> scoreReasons = new ArrayList<>();
    List<String> behaviorTags = new ArrayList<>();
    List<String> riskFlags = new ArrayList<>();
    String sceneLocation;
    String interactionMode;
    int plotGap;
    int plotSignal;
    int plotPressure;
    int plotSceneSignal;
    int plotRelationshipSignal;
    int plotEventSignal;
    int plotContinuitySignal;
    int plotRiskSignal;
    String plotDirectorAction;
    String plotWhyNow;
    int plotDirectorConfidence;
    String plotRiskIfAdvance;
    String requiredUserSignal;
    String sceneMoveKind;
    String sceneMoveTarget;
    String sceneMoveReason;
    int sceneMoveConfidence;
    String userReplyAct;
    int userReplyActConfidence;
    AssistantObligation assistantObligation;
    String recommendedQuickJudgeTier;
    boolean shouldAskQuickJudge;
    List<UserReplyActCandidate> userReplyActCandidates = new ArrayList<>();
    List<LocalConflict> localConflicts = new ArrayList<>();
    String continuityObjective;
    String continuityAcceptedPlan;
    String continuityNextBestMove;
    boolean sceneTransitionNeeded;
    List<String> continuityGuards = new ArrayList<>();
    String updatedAt;
}

class UserReplyActCandidate implements Serializable {
    String act;
    int score;
    int confidence;
    List<String> evidence = new ArrayList<>();
}

class AssistantObligation implements Serializable {
    String type;
    String source;
    int priority;
    List<String> expectedUserActs = new ArrayList<>();
    String reason;
}

class LocalConflict implements Serializable {
    String type;
    String severity;
    String sourceA;
    String sourceB;
    String recommendedAction;
}

class TurnUnderstandingState implements Serializable {
    String primaryAct;
    int confidence;
    List<UserReplyActCandidate> candidates = new ArrayList<>();
    List<LocalConflict> localConflicts = new ArrayList<>();
    AssistantObligation assistantObligation;
    String recommendedQuickJudgeTier;
    boolean shouldAskQuickJudge;
    String sceneMoveKind;
    String sceneMoveTarget;
    String sceneMoveReason;
    int sceneMoveConfidence;
    String updatedAt;
}

class DialogueContinuityState implements Serializable {
    String currentObjective;
    String pendingUserOffer;
    String acceptedPlan;
    String lastAssistantQuestion;
    boolean userAnsweredLastQuestion;
    boolean sceneTransitionNeeded;
    String nextBestMove;
    List<String> mustNotContradict = new ArrayList<>();
    int confidence;
    String updatedAt;
}

class QuickJudgeStatus implements Serializable {
    boolean attempted;
    boolean used;
    boolean applied;
    String status;
    String reason;
    int confidence;
    String primaryIntent;
    String secondaryIntent;
    String emotion;
    String sharedObjective;
    String nextBestMove;
    String replyPriority;
    int triggerScore;
    List<String> triggerReasons = new ArrayList<>();
    List<String> suppressedReasons = new ArrayList<>();
    String updatedAt;
}

class PendingRepairCue implements Serializable {
    String type;
    String instruction;
    int confidence;
    String createdAt;
}

class RelationshipScoreCalibration implements Serializable {
    boolean used;
    int closenessDelta;
    int trustDelta;
    int resonanceDelta;
    int confidence;
    String reason;
    String createdAt;
    int sourceTurn;

    static RelationshipScoreCalibration none(String reason) {
        RelationshipScoreCalibration calibration = new RelationshipScoreCalibration();
        calibration.used = false;
        calibration.reason = reason == null ? "" : reason;
        return calibration;
    }

    boolean shouldApply() {
        return used && confidence >= 70 && (closenessDelta != 0 || trustDelta != 0 || resonanceDelta != 0);
    }
}

class SceneConsistencyAudit implements Serializable {
    boolean consistent;
    boolean fixed;
    String reason;
}

class RealityAudit implements Serializable {
    boolean timeConsistent;
    boolean weatherConsistent;
    boolean sceneConsistent;
    boolean interactionConsistent;
    boolean grounded;
    List<String> notes = new ArrayList<>();
}

class HumanizationAudit implements Serializable {
    boolean feltHeard;
    boolean answeredCoreQuestion;
    boolean usedMemoryNaturally;
    boolean initiativeAppropriate;
    boolean sceneConsistent;
    boolean emotionMatched;
    boolean overacted;
    boolean tooMechanical;
    List<String> notes = new ArrayList<>();
}

class RelationshipState implements Serializable {
    int closeness;
    int trust;
    int resonance;
    int affectionScore;
    String relationshipStage;
    String ending;
    String stageProgressHint;
    int stagnationLevel;
    String routeTag;
    String endingCandidate;
    String relationshipFeedback;
}

class StoryEventProgress implements Serializable {
    List<String> triggeredEventIds = new ArrayList<>();
    String lastTriggeredEventId;
    Map<String, Integer> eventCooldownUntilTurn = new LinkedHashMap<>();
    String lastTriggeredTitle;
    String lastTriggeredTheme;
    String currentRouteTheme;
    String nextExpectedDirection;
}

class SceneState implements Serializable {
    String location;
    String subLocation;
    String interactionMode;
    String timeOfScene;
    String weatherMood;
    boolean transitionPending;
    int transitionLockUntilTurn;
    int lastConfirmedSceneTurn;
    String sceneSummary;
    String updatedAt;
}

class ArcCheckpointSummary implements Serializable {
    int arcIndex;
    int beatStart;
    int beatEnd;
    String title;
    String routeTheme;
    String relationshipSummary;
    String sceneSummary;
    String endingTendency;
    String updatedAt;
}

class PlotArcState implements Serializable {
    int beatIndex;
    int arcIndex;
    String phase;
    String sceneFrame;
    List<String> openThreads = new ArrayList<>();
    int lastPlotTurn;
    int forcePlotAtTurn;
    int plotPressure;
    String plotProgress;
    String nextBeatHint;
    boolean checkpointReady;
    String runStatus;
    String endingCandidate;
    boolean canSettleScore;
    boolean canContinue;
    ArcCheckpointSummary latestArcSummary;
    String updatedAt;
}

class SessionRecord implements Serializable {
    String id;
    String visitorId;
    String agentId;
    String createdAt;
    String lastActiveAt;
    String memoryExpireAt;
    int userTurnCount;
    RelationshipState relationshipState;
    MemorySummary memorySummary;
    StoryEventProgress storyEventProgress;
    EmotionState emotionState;
    PlotState plotState;
    PlotArcState plotArcState;
    SceneState sceneState;
    PresenceState presenceState;
    RelationalTensionState tensionState;
    String pendingChoiceEventId;
    List<ChoiceOption> pendingChoices = new ArrayList<>();
    String pendingEventContext;
    String lastProactiveMessageAt;
    IntentState lastIntentState;
    ResponsePlan lastResponsePlan;
    HumanizationAudit lastHumanizationAudit;
    RealityAudit lastRealityAudit;
    PlotGateDecision lastPlotGateDecision;
    TurnContext lastTurnContext;
    DialogueContinuityState dialogueContinuityState;
    QuickJudgeStatus lastQuickJudgeStatus;
    QuickJudgeDecision pendingQuickJudgeCorrection;
    String pendingQuickJudgeCorrectionAt;
    PendingRepairCue pendingRepairCue;
    RelationshipScoreCalibration pendingRelationshipCalibration;
    String pendingRelationshipCalibrationAt;
    String plotPressureMode;
}

class AnalyticsEvent implements Serializable {
    String id;
    String type;
    String createdAt;
    String visitorId;
    String sessionId;
    String agentId;
    String restoredSessionId;
    String triggeredEventId;
    Boolean fallbackUsed;
}

class AppState implements Serializable {
    List<VisitorRecord> visitors = new ArrayList<>();
    List<SessionRecord> sessions = new ArrayList<>();
    List<ConversationMessage> messages = new ArrayList<>();
    List<AnalyticsEvent> analyticsEvents = new ArrayList<>();

    static AppState empty() {
        return new AppState();
    }
}

class TurnEvaluation {
    final RelationshipState nextState;
    final Delta affectionDelta;
    final List<String> behaviorTags;
    final List<String> riskFlags;
    final List<String> scoreReasons;
    final boolean stageChanged;
    final String stageProgress;
    final String relationshipFeedback;

    TurnEvaluation(RelationshipState nextState, Delta affectionDelta) {
        this(nextState, affectionDelta, List.of(), List.of(), false, "", "", List.of());
    }

    TurnEvaluation(
            RelationshipState nextState,
            Delta affectionDelta,
            List<String> behaviorTags,
            List<String> riskFlags,
            boolean stageChanged,
            String stageProgress,
            String relationshipFeedback
    ) {
        this(nextState, affectionDelta, behaviorTags, riskFlags, stageChanged, stageProgress, relationshipFeedback, List.of());
    }

    TurnEvaluation(
            RelationshipState nextState,
            Delta affectionDelta,
            List<String> behaviorTags,
            List<String> riskFlags,
            boolean stageChanged,
            String stageProgress,
            String relationshipFeedback,
            List<String> scoreReasons
    ) {
        this.nextState = nextState;
        this.affectionDelta = affectionDelta;
        this.behaviorTags = behaviorTags;
        this.riskFlags = riskFlags;
        this.stageChanged = stageChanged;
        this.stageProgress = stageProgress;
        this.relationshipFeedback = relationshipFeedback;
        this.scoreReasons = scoreReasons;
    }
}

class Delta implements Serializable {
    int closeness;
    int trust;
    int resonance;
    int total;
}

class MemoryRecall implements Serializable {
    final String tier;
    final List<String> selectedMemories;
    final String mergedText;

    MemoryRecall(String tier, List<String> selectedMemories, String mergedText) {
        this.tier = tier;
        this.selectedMemories = selectedMemories;
        this.mergedText = mergedText;
    }
}

class InputInspection {
    final boolean blocked;
    final String reason;
    final String safeMessage;

    InputInspection(boolean blocked, String reason, String safeMessage) {
        this.blocked = blocked;
        this.reason = reason;
        this.safeMessage = safeMessage;
    }
}

class LlmRequest {
    final AgentProfile agent;
    final RelationshipState relationshipState;
    final List<ConversationSnippet> shortTermContext;
    final String longTermSummary;
    final String recalledMemoryTier;
    final String recalledMemoryText;
    final String currentUserMood;
    final String responseCadence;
    final String responseDirective;
    final StoryEvent event;
    final String userMessage;
    final TimeContext timeContext;
    final WeatherContext weatherContext;
    final String sceneFrame;
    final SceneState sceneState;
    final MemoryUsePlan memoryUsePlan;
    final EmotionState emotionState;
    final String replySource;
    final TemperamentProfile temperamentProfile;
    final String searchContext;
    final IntentState intentState;
    final ResponsePlan responsePlan;
    final UncertaintyState uncertaintyState;
    final InitiativeDecision initiativeDecision;
    final List<MemoryIntentBinding> memoryIntentBindings;
    final RealityEnvelope realityEnvelope;
    final RelationalTensionState tensionState;
    final PlotGateDecision plotGateDecision;
    final DialogueContinuityState dialogueContinuityState;
    final PendingRepairCue pendingRepairCue;
    final TurnContext turnContext;

    LlmRequest(
            AgentProfile agent,
            RelationshipState relationshipState,
            List<ConversationSnippet> shortTermContext,
            String longTermSummary,
            String recalledMemoryTier,
            String recalledMemoryText,
            String currentUserMood,
            String responseCadence,
            String responseDirective,
            StoryEvent event,
            String userMessage,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String sceneFrame,
            SceneState sceneState,
            MemoryUsePlan memoryUsePlan,
            EmotionState emotionState,
            String replySource,
            TemperamentProfile temperamentProfile,
            String searchContext,
            IntentState intentState,
            ResponsePlan responsePlan,
            UncertaintyState uncertaintyState,
            InitiativeDecision initiativeDecision,
            List<MemoryIntentBinding> memoryIntentBindings,
            RealityEnvelope realityEnvelope,
            RelationalTensionState tensionState,
            PlotGateDecision plotGateDecision,
            DialogueContinuityState dialogueContinuityState
    ) {
        this(
                agent,
                relationshipState,
                shortTermContext,
                longTermSummary,
                recalledMemoryTier,
                recalledMemoryText,
                currentUserMood,
                responseCadence,
                responseDirective,
                event,
                userMessage,
                timeContext,
                weatherContext,
                sceneFrame,
                sceneState,
                memoryUsePlan,
                emotionState,
                replySource,
                temperamentProfile,
                searchContext,
                intentState,
                responsePlan,
                uncertaintyState,
                initiativeDecision,
                memoryIntentBindings,
                realityEnvelope,
                tensionState,
                plotGateDecision,
                dialogueContinuityState,
                null,
                null
        );
    }

    LlmRequest(
            AgentProfile agent,
            RelationshipState relationshipState,
            List<ConversationSnippet> shortTermContext,
            String longTermSummary,
            String recalledMemoryTier,
            String recalledMemoryText,
            String currentUserMood,
            String responseCadence,
            String responseDirective,
            StoryEvent event,
            String userMessage,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String sceneFrame,
            SceneState sceneState,
            MemoryUsePlan memoryUsePlan,
            EmotionState emotionState,
            String replySource,
            TemperamentProfile temperamentProfile,
            String searchContext,
            IntentState intentState,
            ResponsePlan responsePlan,
            UncertaintyState uncertaintyState,
            InitiativeDecision initiativeDecision,
            List<MemoryIntentBinding> memoryIntentBindings,
            RealityEnvelope realityEnvelope,
            RelationalTensionState tensionState,
            PlotGateDecision plotGateDecision,
            DialogueContinuityState dialogueContinuityState,
            PendingRepairCue pendingRepairCue
    ) {
        this(
                agent,
                relationshipState,
                shortTermContext,
                longTermSummary,
                recalledMemoryTier,
                recalledMemoryText,
                currentUserMood,
                responseCadence,
                responseDirective,
                event,
                userMessage,
                timeContext,
                weatherContext,
                sceneFrame,
                sceneState,
                memoryUsePlan,
                emotionState,
                replySource,
                temperamentProfile,
                searchContext,
                intentState,
                responsePlan,
                uncertaintyState,
                initiativeDecision,
                memoryIntentBindings,
                realityEnvelope,
                tensionState,
                plotGateDecision,
                dialogueContinuityState,
                pendingRepairCue,
                null
        );
    }

    LlmRequest(
            AgentProfile agent,
            RelationshipState relationshipState,
            List<ConversationSnippet> shortTermContext,
            String longTermSummary,
            String recalledMemoryTier,
            String recalledMemoryText,
            String currentUserMood,
            String responseCadence,
            String responseDirective,
            StoryEvent event,
            String userMessage,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String sceneFrame,
            SceneState sceneState,
            MemoryUsePlan memoryUsePlan,
            EmotionState emotionState,
            String replySource,
            TemperamentProfile temperamentProfile,
            String searchContext,
            IntentState intentState,
            ResponsePlan responsePlan,
            UncertaintyState uncertaintyState,
            InitiativeDecision initiativeDecision,
            List<MemoryIntentBinding> memoryIntentBindings,
            RealityEnvelope realityEnvelope,
            RelationalTensionState tensionState,
            PlotGateDecision plotGateDecision,
            DialogueContinuityState dialogueContinuityState,
            PendingRepairCue pendingRepairCue,
            TurnContext turnContext
    ) {
        this.agent = agent;
        this.relationshipState = relationshipState;
        this.shortTermContext = shortTermContext;
        this.longTermSummary = longTermSummary;
        this.recalledMemoryTier = recalledMemoryTier;
        this.recalledMemoryText = recalledMemoryText;
        this.currentUserMood = currentUserMood;
        this.responseCadence = responseCadence;
        this.responseDirective = responseDirective;
        this.event = event;
        this.userMessage = userMessage;
        this.timeContext = timeContext;
        this.weatherContext = weatherContext;
        this.sceneFrame = sceneFrame;
        this.sceneState = sceneState;
        this.memoryUsePlan = memoryUsePlan;
        this.emotionState = emotionState;
        this.replySource = replySource;
        this.temperamentProfile = temperamentProfile;
        this.searchContext = searchContext;
        this.intentState = intentState;
        this.responsePlan = responsePlan;
        this.uncertaintyState = uncertaintyState;
        this.initiativeDecision = initiativeDecision;
        this.memoryIntentBindings = memoryIntentBindings;
        this.realityEnvelope = realityEnvelope;
        this.tensionState = tensionState;
        this.plotGateDecision = plotGateDecision;
        this.dialogueContinuityState = dialogueContinuityState;
        this.pendingRepairCue = pendingRepairCue;
        this.turnContext = turnContext;
    }
}

class ConversationSnippet {
    final String role;
    final String text;

    ConversationSnippet(String role, String text) {
        this.role = role;
        this.text = text;
    }
}

class LlmResponse {
    final String replyText;
    final String sceneText;
    final String actionText;
    final String speechText;
    final String emotionTag;
    final String confidenceStatus;
    final int tokenUsage;
    final String errorCode;
    final boolean fallbackUsed;
    final String provider;

    LlmResponse(
            String replyText,
            String sceneText,
            String actionText,
            String speechText,
            String emotionTag,
            String confidenceStatus,
            int tokenUsage,
            String errorCode,
            boolean fallbackUsed,
            String provider
    ) {
        this.replyText = replyText;
        this.sceneText = sceneText;
        this.actionText = actionText;
        this.speechText = speechText;
        this.emotionTag = emotionTag;
        this.confidenceStatus = confidenceStatus;
        this.tokenUsage = tokenUsage;
        this.errorCode = errorCode;
        this.fallbackUsed = fallbackUsed;
        this.provider = provider;
    }

    LlmResponse(String replyText, String emotionTag, String confidenceStatus, int tokenUsage, String errorCode, boolean fallbackUsed, String provider) {
        this(replyText, null, null, replyText, emotionTag, confidenceStatus, tokenUsage, errorCode, fallbackUsed, provider);
    }
}

final class IsoTimes {
    private IsoTimes() {
    }

    static String now() {
        return Instant.now().toString();
    }
}
