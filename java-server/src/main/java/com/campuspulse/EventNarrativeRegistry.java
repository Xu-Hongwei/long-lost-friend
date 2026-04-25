package com.campuspulse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class EventNarrativeRegistry {
    private EventNarrativeRegistry() {
    }

    static List<AgentProfile> enrich(List<AgentProfile> source) {
        List<AgentProfile> result = new ArrayList<>();
        for (AgentProfile agent : source) {
            List<StoryEvent> events = new ArrayList<>();
            for (StoryEvent event : agent.storyEvents) {
                events.add(enrichEvent(agent.id, event));
            }
            result.add(new AgentProfile(
                    agent.id,
                    agent.name,
                    agent.archetype,
                    agent.tagline,
                    agent.palette,
                    agent.avatarGlyph,
                    agent.bio,
                    agent.speechStyle,
                    agent.likes,
                    agent.dislikes,
                    agent.relationshipRules,
                    agent.boundaries,
                    agent.openingLine,
                    events
            ));
        }
        return result;
    }

    private static StoryEvent enrichEvent(String roleId, StoryEvent event) {
        boolean routeEvent = event.id.endsWith("_route");
        String category = routeEvent ? "breakthrough" : event.category;
        List<String> stageRange = routeEvent ? List.of("靠近", "确认关系") : event.stageRange;
        int weight = routeEvent ? event.weight + 4 : event.weight;
        int cooldown = routeEvent ? Math.max(event.cooldown, 4) : event.cooldown;
        List<ChoiceOption> choices = themedChoiceSet(roleId, event.id, category, routeEvent);
        EventEffect success = themedEffect(roleId, event, "success", routeEvent);
        EventEffect neutral = themedEffect(roleId, event, "neutral", routeEvent);
        EventEffect fail = themedEffect(roleId, event, "fail", routeEvent);
        List<String> followups = followupsForEvent(event.id);
        boolean keyChoiceEvent = routeEvent || event.keyChoiceEvent || event.affectionBonus >= 4;
        String nextDirection = nextDirectionForRole(roleId, event, "neutral", routeEvent);

        return new StoryEvent(
                event.id,
                event.title,
                event.unlockAtMessages,
                event.minAffection,
                event.theme,
                event.keywordsAny,
                event.affectionBonus,
                category,
                stageRange,
                event.unlockConditions == null ? Map.of() : event.unlockConditions,
                event.blockConditions == null ? Map.of() : event.blockConditions,
                weight,
                cooldown,
                choices,
                success,
                neutral,
                fail,
                followups,
                keyChoiceEvent,
                nextDirection
        );
    }

    private static List<ChoiceOption> themedChoiceSet(String roleId, String eventId, String category, boolean routeEvent) {
        if (routeEvent) {
            return switch (roleId) {
                case "healing" -> List.of(
                        new ChoiceOption(eventId + "_warm", "认真把心意说清楚", "温柔确认双向关系", "success"),
                        new ChoiceOption(eventId + "_soft", "先承认你也在意她", "保留一点继续靠近的余地", "neutral"),
                        new ChoiceOption(eventId + "_miss", "把话题轻轻带开", "容易错过确认时机", "fail")
                );
                case "lively" -> List.of(
                        new ChoiceOption(eventId + "_warm", "接住她的直球", "把未来计划说得更明确", "success"),
                        new ChoiceOption(eventId + "_soft", "先笑着默认靠近", "保留暧昧空间", "neutral"),
                        new ChoiceOption(eventId + "_miss", "用玩笑躲开重点", "会让她的热意落空", "fail")
                );
                case "cool" -> List.of(
                        new ChoiceOption(eventId + "_warm", "郑重给出回应", "让克制变成确定", "success"),
                        new ChoiceOption(eventId + "_soft", "先说你愿意继续靠近", "保留一点观察空间", "neutral"),
                        new ChoiceOption(eventId + "_miss", "装作没听懂", "会让气氛退回去", "fail")
                );
                case "artsy" -> List.of(
                        new ChoiceOption(eventId + "_warm", "把比喻落成现实", "认真回应他的心意", "success"),
                        new ChoiceOption(eventId + "_soft", "先承认你也心动", "让情绪继续发酵", "neutral"),
                        new ChoiceOption(eventId + "_miss", "继续用玩笑绕开", "容易错过最真诚的时刻", "fail")
                );
                default -> List.of(
                        new ChoiceOption(eventId + "_warm", "正面接球", "把默契带进更长的以后", "success"),
                        new ChoiceOption(eventId + "_soft", "先说你也在意对方", "留一点慢慢靠近的空间", "neutral"),
                        new ChoiceOption(eventId + "_miss", "把告白打成岔", "会让热度先降下来", "fail")
                );
            };
        }

        if ("emotion".equals(category)) {
            return switch (roleId) {
                case "healing" -> List.of(
                        new ChoiceOption(eventId + "_warm", "认真把心事交给她", "让她更稳地接住你", "success"),
                        new ChoiceOption(eventId + "_soft", "只轻轻透露一点", "先试探她的靠近", "neutral"),
                        new ChoiceOption(eventId + "_miss", "假装没事带过去", "会把情绪重新压回去", "fail")
                );
                case "cool" -> List.of(
                        new ChoiceOption(eventId + "_warm", "相信他的沉默陪伴", "让信任再往前一步", "success"),
                        new ChoiceOption(eventId + "_soft", "先说一半", "留一点观察空间", "neutral"),
                        new ChoiceOption(eventId + "_miss", "用冷淡挡回去", "容易让气氛收紧", "fail")
                );
                default -> List.of(
                        new ChoiceOption(eventId + "_warm", "顺着情绪认真回应", "把这一刻接住", "success"),
                        new ChoiceOption(eventId + "_soft", "先接一点点", "保留轻松节奏", "neutral"),
                        new ChoiceOption(eventId + "_miss", "急着岔开话题", "会让情绪落空", "fail")
                );
            };
        }

        if ("breakthrough".equals(category)) {
            return List.of(
                    new ChoiceOption(eventId + "_warm", "认真回应这份在意", "让关系真正往前走", "success"),
                    new ChoiceOption(eventId + "_soft", "先承认你也在意", "把突破放慢半步", "neutral"),
                    new ChoiceOption(eventId + "_miss", "把重点轻轻盖过去", "会错过此刻的推进", "fail")
            );
        }

        return switch (roleId) {
            case "lively" -> List.of(
                    new ChoiceOption(eventId + "_warm", "跟上她的节奏", "把热闹变成双向互动", "success"),
                    new ChoiceOption(eventId + "_soft", "轻松接话", "先维持气氛", "neutral"),
                    new ChoiceOption(eventId + "_miss", "让场面凉下来", "会破坏她的热意", "fail")
            );
            case "artsy" -> List.of(
                    new ChoiceOption(eventId + "_warm", "顺着他的画面感回应", "让他觉得你真的懂他", "success"),
                    new ChoiceOption(eventId + "_soft", "轻轻接住他的比喻", "先保留暧昧", "neutral"),
                    new ChoiceOption(eventId + "_miss", "把气氛剪断", "会让灵感感退下去", "fail")
            );
            default -> List.of(
                    new ChoiceOption(eventId + "_warm", "认真接住", "把互动往前推一步", "success"),
                    new ChoiceOption(eventId + "_soft", "轻轻回应", "先把气氛维持住", "neutral"),
                    new ChoiceOption(eventId + "_miss", "顺手带过", "容易错过节奏", "fail")
            );
        };
    }

    private static EventEffect themedEffect(String roleId, StoryEvent event, String outcome, boolean routeEvent) {
        int closeness = switch (outcome) {
            case "success" -> routeEvent ? 3 : 2;
            case "neutral" -> 1;
            default -> routeEvent ? -2 : -1;
        };
        int trust = switch (outcome) {
            case "success" -> routeEvent ? 4 : ("cool".equals(roleId) || "healing".equals(roleId) ? 3 : 2);
            case "neutral" -> routeEvent ? 1 : 0;
            default -> ("cool".equals(roleId) || "healing".equals(roleId)) ? -2 : -1;
        };
        int resonance = switch (outcome) {
            case "success" -> routeEvent ? 4 : Math.max(2, event.affectionBonus);
            case "neutral" -> Math.max(1, event.affectionBonus / 2);
            default -> -1;
        };
        boolean majorNegative = routeEvent && "fail".equals(outcome);
        String routeTag = routeTagForRole(roleId, event, routeEvent, outcome);
        String feedback = feedbackForRole(roleId, event, outcome, routeEvent);
        String nextDirection = nextDirectionForRole(roleId, event, outcome, routeEvent);
        return new EventEffect(closeness, trust, resonance, routeTag, feedback, nextDirection, majorNegative);
    }

    private static String routeTagForRole(String roleId, StoryEvent event, boolean routeEvent, String outcome) {
        if ("fail".equals(outcome) && routeEvent) {
            return "错过观察";
        }
        if (routeEvent) {
            return switch (roleId) {
                case "healing" -> "温柔确认";
                case "lively" -> "高亮靠近";
                case "cool" -> "克制确认";
                case "artsy" -> "现实落点";
                default -> "并肩成队";
            };
        }
        return switch (event.category) {
            case "emotion" -> switch (roleId) {
                case "healing" -> "安抚承接";
                case "cool" -> "边界信任";
                default -> "情绪承接";
            };
            case "breakthrough" -> "关系突破";
            case "conflict" -> "错过观察";
            default -> switch (roleId) {
                case "lively" -> "热闹升温";
                case "artsy" -> "灵感靠近";
                case "sunny" -> "并肩默契";
                default -> "日常升温";
            };
        };
    }

    private static String feedbackForRole(String roleId, StoryEvent event, String outcome, boolean routeEvent) {
        if ("success".equals(outcome)) {
            if (routeEvent) {
                return switch (roleId) {
                    case "healing" -> "你没有让她一个人把温柔说完，这次回应足够让关系往确认迈一步。";
                    case "lively" -> "你接住了她最亮的那一下热意，关系从热闹变成了明确。";
                    case "cool" -> "你给了他足够郑重的回应，克制终于落成了确定。";
                    case "artsy" -> "你把他的比喻接成了现实，这次心意终于有了落点。";
                    default -> "你没有躲开他的直球，这次默契被推成了真正的靠近。";
                };
            }
            return switch (event.category) {
                case "emotion" -> "你这次把情绪接得很稳，对方会更愿意把脆弱交给你。";
                case "breakthrough" -> "你没有回避这份在意，关系明显往前走了一段。";
                default -> "这次互动不只是顺利，而是真的让关系往前推了一小步。";
            };
        }

        if ("neutral".equals(outcome)) {
            return routeEvent
                    ? "你没有把话说死，但也让对方知道这段关系仍值得继续靠近。"
                    : "气氛被留住了，只是还差一点更明确的回应。";
        }

        return routeEvent
                ? "你把最该接住的话轻轻放掉了，关系会先慢下来观察。"
                : "这次回应有些错拍，关系没有立刻掉下去，但热度被按住了。";
    }

    private static String nextDirectionForRole(String roleId, StoryEvent event, String outcome, boolean routeEvent) {
        if ("success".equals(outcome)) {
            if (routeEvent) {
                return switch (roleId) {
                    case "healing" -> "再给她一点稳定回应，你们就会进入真正双向确认。";
                    case "lively" -> "接下来只要继续把热意落到行动里，关系会非常明亮。";
                    case "cool" -> "后续重点不再是试探，而是让确定感持续稳下来。";
                    case "artsy" -> "接下来别让情绪只停在气氛里，让现实行动继续跟上。";
                    default -> "接下来把默契继续延长到日常里，关系会更自然地站稳。";
                };
            }
            return switch (event.category) {
                case "emotion" -> "下一步适合继续承接真实情绪，先把信任补得更满。";
                case "breakthrough" -> "可以期待下一次更明确的双向表达。";
                default -> "关系已经抬头了，下一步可以更主动一点。";
            };
        }

        if ("neutral".equals(outcome)) {
            return routeEvent
                    ? "还没到最后确认的时候，但这条线还能够继续往前。"
                    : "先把气氛守住，再找更合适的时机推进。";
        }

        return routeEvent
                ? "先别急着追赶进度，等情绪回稳后再找修复机会。"
                : "下一步先修复节奏，再谈推进。";
    }

    private static List<String> followupsForEvent(String eventId) {
        return switch (eventId) {
            case "healing_library" -> List.of("healing_cocoa");
            case "healing_cocoa" -> List.of("healing_midnight");
            case "healing_note" -> List.of("healing_walk");
            case "lively_secret" -> List.of("lively_game");
            case "lively_wait" -> List.of("lively_route");
            case "cool_guard" -> List.of("cool_rain");
            case "cool_pause" -> List.of("cool_route");
            case "artsy_page" -> List.of("artsy_bridge");
            case "artsy_letter" -> List.of("artsy_route");
            case "sunny_guard" -> List.of("sunny_storm");
            case "sunny_team" -> List.of("sunny_route");
            default -> List.of();
        };
    }
}
