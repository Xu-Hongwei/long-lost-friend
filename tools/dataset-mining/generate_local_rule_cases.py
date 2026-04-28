from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "testdata" / "local-rules"

TARGET_COUNTS = {
    "scene_move": 180,
    "turn_understanding": 180,
    "quick_judge_trigger": 120,
    "plot_signal": 150,
    "heartbeat": 90,
    "relationship_scoring": 240,
}


def as_list(value) -> list:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def put_if_present(target: dict, key: str, value) -> None:
    if value is not None:
        target[key] = value


def layered_expect(module: str, flat: dict, case: dict) -> dict:
    """Convert terse generation hints into a stable assertion schema.

    The local rules are intentionally interconnected, so the large diagnostic set
    should not turn every label into a hard assertion. Only behavioral boundaries
    go into must/mustNot; preferred labels and numeric tendencies stay in should.
    """
    must: dict = {}
    should: dict = {}
    must_not: dict = {}
    diagnostic: dict = {
        "category": case["id"].rsplit("_", 1)[0],
        "severity": "medium",
        "reason": case.get("note", ""),
    }

    if module == "scene_move":
        if "shouldMove" in flat:
            must["shouldMove"] = flat["shouldMove"]
            if flat["shouldMove"] is False:
                must_not["sceneMoveKind"] = ["move_to", "return_to"]
        if flat.get("shouldCreateSceneText") is False:
            must["shouldCreateSceneText"] = False
        elif flat.get("shouldCreateSceneText") is True:
            should["shouldCreateSceneText"] = True
        if "sceneMoveKind" in flat:
            kind = flat["sceneMoveKind"]
            if kind in ("arrived", "cancel_move", "stay"):
                must["sceneMoveKind"] = kind
                diagnostic["severity"] = "high"
            elif kind == "move_to":
                should["sceneMoveKind"] = ["move_to", "return_to"]
            else:
                should["sceneMoveKind"] = as_list(kind) + (["no_change"] if kind == "topic_only" else [])
        if flat.get("targetLocation"):
            if flat.get("shouldMove") or flat.get("sceneMoveKind") == "arrived":
                should["targetLocation"] = flat["targetLocation"]
            else:
                must_not["targetLocation"] = [flat["targetLocation"]]
        elif flat.get("shouldMove") is False:
            should["targetLocation"] = ""
        put_if_present(should, "interactionMode", flat.get("interactionMode"))
        put_if_present(should, "localConflict", flat.get("localConflict"))

    elif module == "turn_understanding":
        primary = flat.get("primaryAct")
        tier = flat.get("recommendedQuickJudgeTier")
        conflict = flat.get("localConflict")
        if primary == "clarify" or tier == "urgent" or conflict:
            diagnostic["severity"] = "high"
            if primary:
                must["primaryAct"] = primary
            if tier == "urgent":
                must["recommendedQuickJudgeTier"] = "urgent"
            if conflict:
                must["localConflict"] = conflict
        else:
            put_if_present(should, "primaryAct", as_list(primary) if primary else None)
            put_if_present(should, "recommendedQuickJudgeTier", as_list(tier) if tier else None)
        put_if_present(should, "assistantObligation", flat.get("assistantObligation"))
        put_if_present(should, "secondaryCandidate", flat.get("secondaryCandidate"))
        put_if_present(should, "sceneMoveKind", flat.get("sceneMoveKind"))
        put_if_present(should, "sceneMoveTarget", flat.get("sceneMoveTarget"))
        if flat.get("sceneMoveKind") == "topic_only":
            must_not["sceneMoveKind"] = ["move_to", "return_to"]

    elif module == "quick_judge_trigger":
        if "shouldStart" in flat:
            must["shouldStart"] = flat["shouldStart"]
        tier = flat.get("tier")
        if tier in ("urgent", "background", "skip"):
            must["tier"] = tier
            if tier == "urgent":
                diagnostic["severity"] = "high"
            if tier == "background":
                must["waitMs"] = 0
        elif tier:
            should["tier"] = as_list(tier)
        put_if_present(should, "reason", flat.get("reason"))
        put_if_present(should, "suppressedReason", flat.get("suppressedReason"))
        put_if_present(should, "minTriggerScore", flat.get("minTriggerScore"))

    elif module == "plot_signal":
        if "advanced" in flat:
            if flat["advanced"] is False:
                # Holding is a safety boundary; advancing is only a strong tendency
                # because gap/pressure/protection gates can still block it.
                must["advanced"] = False
                must_not["plotDirectorAction"] = ["advance_plot"]
            else:
                should["advanced"] = True
        action = flat.get("plotDirectorAction")
        if action in ("transition_only", "heartbeat_nudge"):
            must["plotDirectorAction"] = action
        elif action:
            should["plotDirectorAction"] = as_list(action)
        for key in ("minPlotSignal", "minPlotGap", "minPlotPressure", "plotSignal", "plotPressureAfter", "plotSignalAdjustment", "reason", "macroScoreType"):
            put_if_present(should, key, flat.get(key))
        if "meta_repair" in str(case.get("context", {}).get("primaryIntent", "")):
            must_not["plotDirectorAction"] = ["advance_plot"]
            diagnostic["severity"] = "high"

    elif module == "heartbeat":
        for key in ("clearObjective", "shouldNotAskSamePlan", "plotSignalPreserved", "plotPressurePreserved", "plotGapPreserved", "shouldApplyPendingRepair"):
            if key in flat:
                must[key] = flat[key]
        for key in ("shouldPreferLastUserQuestion", "shouldAvoidGenericCompanion", "replyFocus", "replyCadence", "shouldBeShort", "shouldNotForceScene", "nextBestMoveContains"):
            put_if_present(should, key, flat.get(key))
        if flat.get("shouldNotAskSamePlan") or flat.get("shouldApplyPendingRepair"):
            diagnostic["severity"] = "high"

    elif module == "relationship_scoring":
        if flat.get("shouldNotPunish"):
            must["shouldNotPunish"] = True
            diagnostic["severity"] = "high"
        for key in ("closenessDeltaMax", "trustDeltaMax", "resonanceDeltaMax"):
            put_if_present(must, key, flat.get(key))
        for key in ("acts", "tags", "possibleActs", "closenessDeltaMin", "trustDeltaMin", "resonanceDeltaMin"):
            put_if_present(should, key, flat.get(key))
        if flat.get("riskFlags"):
            should["riskFlags"] = flat["riskFlags"]

    else:
        should.update(flat)

    return {
        "must": must,
        "should": should,
        "mustNot": must_not,
        "diagnostic": diagnostic,
    }


def normalize_case(case: dict) -> dict:
    normalized = dict(case)
    flat_expect = normalized.get("expect", {})
    if not isinstance(flat_expect, dict):
        raise ValueError(f"case {normalized.get('id')} has non-object expect")
    normalized["expect"] = layered_expect(str(normalized.get("module", "")), flat_expect, normalized)
    return normalized


def write_jsonl(name: str, cases: list[dict]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for case in cases:
            handle.write(json.dumps(normalize_case(case), ensure_ascii=False, separators=(",", ":")) + "\n")


def scene_move_cases() -> list[dict]:
    cases: list[dict] = []
    topic_places = [
        ("图书馆", "操场", "你喜欢图书馆吗？"),
        ("操场", "图书馆", "你觉得操场晚上适合散步吗？"),
        ("食堂", "图书馆", "你喜欢食堂哪道菜？"),
        ("宿舍", "操场", "宿舍楼下会不会很安静？"),
        ("外面", "图书馆", "外面的雨声是不是挺适合发呆？"),
        ("市区", "图书馆", "你觉得校外的夜市热闹吗？"),
        ("篮球场", "食堂", "你喜欢篮球场那种吵吵闹闹的感觉吗？"),
        ("湖边", "操场", "湖边黄昏是不是很适合拍照？"),
    ]
    for index, (place, current, text) in enumerate(topic_places, 1):
        cases.append({
            "id": f"scene_topic_place_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": current, "acceptedPlan": "", "sceneTransitionNeeded": False},
            "expect": {"sceneMoveKind": "topic_only", "shouldMove": False, "targetLocation": "", "shouldCreateSceneText": False},
            "sourceInspiredBy": ["NaturalConv"],
            "note": f"地点词 {place} 是话题，不是移动目标。",
        })

    explicit_moves = [
        ("操场", "图书馆", "我们去操场走走吧，边走边聊。", "mixed_transition"),
        ("食堂", "操场", "先去食堂看看吧，我有点饿了。", "face_to_face"),
        ("图书馆", "食堂", "要不去图书馆自习一会儿？", "face_to_face"),
        ("宿舍", "图书馆", "我送你回宿舍吧，别一个人走夜路。", "face_to_face"),
        ("外面", "图书馆", "出去看看小雨吧。", "face_to_face"),
        ("市区", "学校", "我们出校去市区逛逛吧。", "face_to_face"),
        ("回去的路上", "食堂", "那我们一起往回走，路上慢慢说。", "mixed_transition"),
        ("热饮摊附近", "图书馆", "去买杯热饮吧，手会暖一点。", "face_to_face"),
    ]
    for index, (target, current, text, mode) in enumerate(explicit_moves, 1):
        cases.append({
            "id": f"scene_explicit_move_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": current, "acceptedPlan": "", "sceneTransitionNeeded": False},
            "expect": {"sceneMoveKind": "move_to", "shouldMove": True, "targetLocation": target, "interactionMode": mode, "shouldCreateSceneText": True},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "明确移动目标，需要更新场景。",
        })

    corrections = [
        ("图书馆", "我们不是已经在图书馆了吗？"),
        ("食堂", "不对啊，我们已经在食堂了。"),
        ("操场", "都到操场了，别再写路上了。"),
        ("宿舍", "不是已经到宿舍楼下了吗？"),
        ("外面", "我们已经在外面了呀。"),
        ("热饮摊附近", "不是已经到热饮摊这边了吗？"),
    ]
    for index, (place, text) in enumerate(corrections, 1):
        cases.append({
            "id": f"scene_arrived_correction_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": place, "acceptedPlan": f"一起去{place}", "sceneTransitionNeeded": True},
            "expect": {"sceneMoveKind": "arrived", "shouldMove": False, "targetLocation": place, "localConflict": "scene_target_already_current"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "用户纠正已经到达目标地点。",
        })

    cancels = [
        ("食堂", "先不去食堂了，就在这里坐会儿吧。"),
        ("操场", "别去操场了，外面风太大。"),
        ("图书馆", "先别去图书馆，我想把话说完。"),
        ("宿舍", "不用送我回宿舍了，我想再待一下。"),
        ("外面", "算了，不出去看雨了。"),
        ("市区", "今天不去市区了，下次吧。"),
    ]
    for index, (target, text) in enumerate(cancels, 1):
        cases.append({
            "id": f"scene_cancel_move_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": "图书馆", "acceptedPlan": f"一起去{target}", "sceneTransitionNeeded": True},
            "expect": {"sceneMoveKind": "cancel_move", "shouldMove": False, "shouldCreateSceneText": False},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "用户取消当前移动目标。",
        })

    implicit = [
        ("热饮摊附近", "一起去买杯热饮", "好啊，听你的。"),
        ("食堂", "一起去食堂吃点东西", "可以，那就走吧。"),
        ("操场", "去操场散散步", "嗯，我们过去吧。"),
        ("图书馆", "去图书馆坐一会儿", "好呀，我跟你一起。"),
        ("回去的路上", "一起往回走", "那就这样，边走边说。"),
        ("外面", "出去看看小雨", "走吧，去看看。"),
    ]
    for index, (target, plan, text) in enumerate(implicit, 1):
        cases.append({
            "id": f"scene_implicit_accept_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": "图书馆", "acceptedPlan": plan, "sceneTransitionNeeded": True},
            "expect": {"sceneMoveKind": "move_to", "shouldMove": True, "targetLocation": target, "shouldCreateSceneText": True},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "短句接受上一轮计划，允许隐式移动。",
        })

    ambient = [
        "风好大，你会不会冷？",
        "雨声好明显，听着还挺安静的。",
        "今天好冷，你平时怕冷吗？",
        "天气真好，感觉人也轻一点。",
        "外面灯光有点暖，你喜欢这种氛围吗？",
        "操场那边好像很热闹，你会嫌吵吗？",
    ]
    for index, text in enumerate(ambient, 1):
        cases.append({
            "id": f"scene_ambient_topic_{index:03d}",
            "module": "scene_move",
            "userMessage": text,
            "context": {"currentLocation": "图书馆", "acceptedPlan": "", "sceneTransitionNeeded": False},
            "expect": {"sceneMoveKind": "topic_only", "shouldMove": False, "targetLocation": ""},
            "sourceInspiredBy": ["NaturalConv"],
            "note": "氛围/天气只作为话题，不自动换场。",
        })

    route_targets = ["图书馆", "操场", "食堂", "宿舍楼下", "湖边", "热饮摊附近", "社团教室", "教学楼走廊", "夜市", "篮球场", "校门口", "回去的路上"]
    route_currents = ["教学楼", "图书馆", "操场", "食堂", "宿舍楼下", "湖边"]
    move_templates = [
        ("我们去{target}吧，路上慢慢说。", "mixed_transition"),
        ("先去{target}看看，可以吗？", "face_to_face"),
        ("要不换到{target}，那里可能更合适。", "face_to_face"),
        ("我想去{target}待一会儿。", "face_to_face"),
        ("那边好像是{target}，我们过去看看？", "mixed_transition"),
        ("如果你不介意，我们往{target}那边走。", "mixed_transition"),
    ]
    for index in range(1, 61):
        target = route_targets[(index - 1) % len(route_targets)]
        current = route_currents[(index + 1) % len(route_currents)]
        if target == current:
            current = route_currents[(index + 2) % len(route_currents)]
        template, mode = move_templates[((index - 1) // len(route_targets)) % len(move_templates)]
        cases.append({
            "id": f"scene_explicit_matrix_{index:03d}",
            "module": "scene_move",
            "userMessage": template.format(target=target),
            "context": {"currentLocation": current, "acceptedPlan": "", "sceneTransitionNeeded": False},
            "expect": {"sceneMoveKind": "move_to", "shouldMove": True, "targetLocation": target, "interactionMode": mode, "shouldCreateSceneText": True},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "CrossWOZ 目标域切换结构改写为校园移动目标。",
        })

    cancel_templates = [
        "先别去{target}了，我想在这里多待一下。",
        "算了，{target}下次再去吧。",
        "我现在不太想往{target}走。",
        "等一下，别急着去{target}。",
        "{target}先放一放，我想把刚才的话说完。",
        "今天不去{target}了，可以吗？",
    ]
    for index in range(1, 31):
        target = route_targets[(index + 2) % len(route_targets)]
        template = cancel_templates[((index - 1) // len(route_targets)) % len(cancel_templates)]
        cases.append({
            "id": f"scene_cancel_matrix_{index:03d}",
            "module": "scene_move",
            "userMessage": template.format(target=target),
            "context": {"currentLocation": "图书馆", "acceptedPlan": f"一起去{target}", "sceneTransitionNeeded": True},
            "expect": {"sceneMoveKind": "cancel_move", "shouldMove": False, "shouldCreateSceneText": False},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "取消目标地点，不应继续触发转场。",
        })

    arrived_templates = [
        "不是已经走到{place}这边了吗？",
        "我们现在就在{place}，别再写路上了。",
        "刚刚不是已经走到{place}了？",
        "我记得我们已经在{place}这边了。",
    ]
    for index in range(1, 25):
        place = route_targets[(index - 1) % len(route_targets)]
        template = arrived_templates[((index - 1) // len(route_targets)) % len(arrived_templates)]
        cases.append({
            "id": f"scene_arrived_matrix_{index:03d}",
            "module": "scene_move",
            "userMessage": template.format(place=place),
            "context": {"currentLocation": place, "acceptedPlan": f"一起去{place}", "sceneTransitionNeeded": True},
            "expect": {"sceneMoveKind": "arrived", "shouldMove": False, "targetLocation": place, "localConflict": "scene_target_already_current"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "到达纠错矩阵，防止重复移动。",
        })

    # Pad with campus location preference variants to cover keyword false positives.
    topic_templates = [
        "你喜欢{place}这种地方吗？",
        "你讨厌{place}那种氛围吗？",
        "你觉得{place}适合聊天吗？",
        "你会不会觉得{place}太吵？",
        "{place}有没有让你放松一点？",
        "为什么有些人会喜欢{place}这种地方？",
        "如果只聊天，不去{place}，你会介意吗？",
        "{place}这个词听起来会不会很有画面感？",
        "你觉得{place}更像安静的地方还是热闹的地方？",
        "说到{place}，你会想到什么？",
    ]
    places = ["图书馆", "操场", "食堂", "宿舍", "湖边", "夜市", "篮球场", "社团教室", "教学楼走廊", "热饮摊", "校门口", "市区"]
    for place in places:
        for template in topic_templates:
            if len(cases) >= TARGET_COUNTS["scene_move"]:
                break
            cases.append({
                "id": f"scene_topic_matrix_{len(cases)+1:03d}",
                "module": "scene_move",
                "userMessage": template.format(place=place),
                "context": {"currentLocation": "教学楼", "acceptedPlan": "", "sceneTransitionNeeded": False},
                "expect": {"sceneMoveKind": "topic_only", "shouldMove": False, "targetLocation": ""},
                "sourceInspiredBy": ["NaturalConv"],
                "note": "地点偏好矩阵样例，防止关键词误触发移动。",
            })
    return cases[:TARGET_COUNTS["scene_move"]]


def turn_understanding_cases() -> list[dict]:
    cases: list[dict] = []
    answers = [
        ("你平时喜欢吃什么菜？", "我其实还好，只要能吃饱就可以。", "answer_question", "skip"),
        ("你喜欢下雨天吗？", "喜欢，但不太喜欢被淋湿。", "answer_question", "skip"),
        ("你今天累不累？", "有一点，不过和你说话会轻松些。", "answer_question", "skip"),
        ("你会不会觉得我话太少？", "不会，我反而觉得这样挺舒服。", "answer_question", "skip"),
        ("你想先去哪边？", "我想先去图书馆。", ["answer_question", "scene_move"], "skip"),
        ("你是不是有点在意我？", "嗯……其实有一点。", ["answer_question", "romantic_probe"], ["skip", "opportunistic"]),
    ]
    for index, (last, text, act, tier) in enumerate(answers, 1):
        cases.append({
            "id": f"tu_answer_question_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": last, "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": act, "assistantObligation": "answer_question", "recommendedQuickJudgeTier": tier},
            "sourceInspiredBy": ["CPED", "CrossWOZ"],
            "note": "回答上一轮问题。",
        })

    acceptances = ["可以。", "好啊。", "那就走吧。", "听你的。", "嗯，过去吧。", "好呀，我跟你一起。", "行，边走边说。", "那就这样。"]
    for index, text in enumerate(acceptances, 1):
        cases.append({
            "id": f"tu_accept_plan_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "要不要去食堂看看？", "activeObjective": "一起去食堂", "sceneTransitionNeeded": True, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": "accept_plan", "assistantObligation": "accept_plan", "recommendedQuickJudgeTier": "opportunistic"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "计划上下文中的短接受。",
        })

    repairs = [
        "不是这个意思，我问的是你喜欢什么。",
        "你没回答我刚才的问题。",
        "你在说什么？",
        "不对，我们已经在图书馆了。",
        "我问的是你，不是问去哪。",
        "别转移话题。",
        "你又重复刚才那句了。",
        "不是吧，怎么突然去食堂？",
    ]
    for index, text in enumerate(repairs, 1):
        cases.append({
            "id": f"tu_meta_repair_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "那我们去操场吧。", "activeObjective": "一起去操场", "sceneTransitionNeeded": True, "primaryIntent": "meta_repair"},
            "expect": {"primaryAct": "clarify", "recommendedQuickJudgeTier": "urgent", "localConflict": "user_self_rescue"},
            "sourceInspiredBy": ["CPED"],
            "note": "用户自救/纠错。",
        })

    defers = ["等一下，先别走。", "晚点再去吧。", "先不去了。", "过会儿再说。", "下次吧。", "我现在不想动。"]
    for index, text in enumerate(defers, 1):
        cases.append({
            "id": f"tu_defer_or_cancel_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "那我们现在过去吧。", "activeObjective": "一起去食堂", "sceneTransitionNeeded": True, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": "defer", "recommendedQuickJudgeTier": "urgent", "localConflict": "user_cancels_active_objective"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "用户暂停/取消当前计划。",
        })

    emotion = [
        "今天其实有点难受，不太想说太多。",
        "我有点紧张，怕说错话。",
        "刚才那句话让我安心了一点。",
        "我不知道为什么，突然有点失落。",
        "我其实挺开心你还记得。",
        "有时候我会觉得自己很笨。",
    ]
    for index, text in enumerate(emotion, 1):
        cases.append({
            "id": f"tu_emotion_share_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "你今天怎么样？", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "emotion_share"},
            "expect": {"primaryAct": "emotion_share", "recommendedQuickJudgeTier": "skip"},
            "sourceInspiredBy": ["CPED", "MPDD"],
            "note": "情绪表达要被优先承接。",
        })

    counter = [
        ("要不换个地方，去操场吧。", "操场"),
        ("不如去图书馆，那里安静点。", "图书馆"),
        ("还是先去食堂吧，我有点饿。", "食堂"),
        ("换个地方吧，外面透透气。", "外面"),
        ("我想先回宿舍楼下。", "宿舍"),
        ("我们路上说吧。", "回去的路上"),
    ]
    for index, (text, target) in enumerate(counter, 1):
        cases.append({
            "id": f"tu_counter_offer_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "我们去食堂看看？", "activeObjective": "一起去食堂", "sceneTransitionNeeded": True, "primaryIntent": "scene_push"},
            "expect": {"primaryAct": "scene_move", "secondaryCandidate": "counter_offer", "sceneMoveTarget": target},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "用户改约目标。",
        })

    advice_texts = [
        "你觉得我该怎么跟室友解释？",
        "如果我有点紧张，应该怎么开口？",
        "你会建议我先休息还是继续复习？",
        "我不知道要不要参加社团活动，你怎么看？",
        "如果对方一直不回消息，我该等吗？",
        "我想道歉，但不知道怎么说比较自然。",
        "明天考试前我该怎么安排时间？",
        "如果我不想去人多的地方，怎么拒绝比较好？",
    ]
    for index, text in enumerate(advice_texts, 1):
        cases.append({
            "id": f"tu_advice_seek_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "你可以慢慢说。", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "advice_seek"},
            "expect": {"primaryAct": ["small_talk", "answer_question"], "recommendedQuickJudgeTier": "skip"},
            "sourceInspiredBy": ["CPED"],
            "note": "建议请求应优先回答，不应误判为剧情推进。",
        })

    romantic_texts = [
        "你刚才是不是有点担心我？",
        "如果我说想多待一会儿，你会不会介意？",
        "我是不是可以理解成你在陪我？",
        "你这样说会让我有点在意。",
        "你是不是也觉得今天的气氛不太一样？",
        "如果我有点期待，是不是太明显了？",
        "你刚刚没有躲开，我有点开心。",
        "我想知道你是真的愿意听我说吗？",
    ]
    for index, text in enumerate(romantic_texts, 1):
        cases.append({
            "id": f"tu_romantic_probe_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "我会在这里听你说。", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "romantic_probe"},
            "expect": {"primaryAct": "romantic_probe", "recommendedQuickJudgeTier": "opportunistic"},
            "sourceInspiredBy": ["CPED", "MPDD"],
            "note": "暧昧探测属于高价值但不一定 urgent。",
        })

    memory_questions = [
        "你还记得我上次说怕冷吗？",
        "我之前说过喜欢靠窗的位置，你还记得吗？",
        "你记不记得我不喜欢人太多？",
        "刚才我说想慢一点，你有听进去吗？",
        "你还记得我说过食堂太吵吗？",
        "我前面问你的问题，你是不是忘了？",
        "你刚刚答应要陪我走慢一点，还算数吗？",
        "你记得我们说好先不换地方吗？",
    ]
    for index, text in enumerate(memory_questions, 1):
        cases.append({
            "id": f"tu_memory_question_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "我记住了。", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "question_check"},
            "expect": {"primaryAct": ["small_talk", "clarify"], "recommendedQuickJudgeTier": ["opportunistic", "urgent"]},
            "sourceInspiredBy": ["CPED"],
            "note": "记忆核对需要承接上下文。",
        })

    accept_templates = ["可以，去{target}吧。", "好，那就去{target}。", "嗯，往{target}走吧。", "听你的，去{target}也行。"]
    accept_targets = ["图书馆", "操场", "食堂", "湖边", "热饮摊附近", "社团教室"]
    for index in range(1, 25):
        target = accept_targets[(index - 1) % len(accept_targets)]
        template = accept_templates[((index - 1) // len(accept_targets)) % len(accept_templates)]
        cases.append({
            "id": f"tu_accept_plan_matrix_{index:03d}",
            "module": "turn_understanding",
            "userMessage": template.format(target=target),
            "context": {"lastAssistant": f"要不要去{target}？", "activeObjective": f"一起去{target}", "sceneTransitionNeeded": True, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": ["accept_plan", "scene_move"], "assistantObligation": "accept_plan", "sceneMoveTarget": target, "recommendedQuickJudgeTier": "opportunistic"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "接受计划同时带目标地点。",
        })

    topic_prompts = [
        "你觉得图书馆适合聊天吗？",
        "你喜欢操场晚上那种风吹过来的感觉吗？",
        "食堂会不会太吵，还是反而有生活气？",
        "为什么有人喜欢在湖边待着？",
        "宿舍楼下有没有让人放松一点？",
        "教学楼走廊是不是更适合发呆？",
        "社团教室那种地方会让你想起什么？",
        "夜市在你印象里更像热闹还是拥挤？",
        "篮球场会不会太吵，还是挺有活力？",
        "校门口是不是总有一种要告别的感觉？",
        "热饮摊附近是不是很适合等人？",
        "市区那种灯光会让你放松还是紧张？",
        "如果只是聊到图书馆，不是真的过去，你会想到什么？",
        "操场这个词听起来像不像很适合沉默？",
        "说到食堂，你第一反应是热闹还是排队？",
        "湖边黄昏听起来会不会有点太文艺？",
        "你会喜欢别人约你去宿舍楼下聊一会儿吗？",
        "社团教室是不是有种还没散场的感觉？",
        "热饮摊这个地方会不会让人比较容易开口？",
        "夜市适合聊天吗，还是只适合边走边看？",
        "你觉得校门口适合说正经话吗？",
        "篮球场这种地方会不会让你分心？",
        "教学楼走廊晚上会不会有点空？",
        "市区太亮的时候，人是不是反而不容易安静？",
        "你会因为一个地方安静就更愿意说真话吗？",
        "如果地点只是话题，不是真的出发，你能分得出来吗？",
        "你喜欢聊地点背后的感觉，还是更喜欢直接去那里？",
        "同一个地方白天和晚上差别会不会很大？",
        "你觉得人少的地方一定更适合聊天吗？",
        "有些地方只是想想就够了，不一定要过去吧？",
    ]
    for index, text in enumerate(topic_prompts, 1):
        cases.append({
            "id": f"tu_topic_only_extra_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "今天风有点凉。", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "question_check"},
            "expect": {"primaryAct": "topic_only", "sceneMoveKind": "topic_only", "recommendedQuickJudgeTier": "skip"},
            "sourceInspiredBy": ["NaturalConv"],
            "note": "地点话题补充样例。",
        })

    soft_chats = [
        "今天路上人比平时少一点。",
        "刚才那阵风还挺舒服的。",
        "我突然想喝点热的。",
        "这个时间校园里好安静。",
        "我刚下课，脑子还有点空。",
        "今天好像没有那么冷。",
        "我看到有人抱着一摞书跑过去。",
        "这会儿灯都亮起来了。",
        "我有点饿，但还不是特别想吃。",
        "今晚好像挺适合慢慢说话。",
        "刚才有人在操场那边笑得很大声。",
        "我发现自己走路会不自觉慢下来。",
        "这种天气会让人想发呆。",
        "我刚刚差点把伞忘在教室。",
        "有时候校园晚上比白天更真实。",
    ]
    for index, text in enumerate(soft_chats, 1):
        cases.append({
            "id": f"tu_light_chat_extra_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "你今天怎么样？", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": "small_talk", "recommendedQuickJudgeTier": "skip"},
            "sourceInspiredBy": ["NaturalConv"],
            "note": "自然轻聊天不应触发重判断。",
        })

    ambiguous_scene = [
        ("那边好像安静一点，不过我只是随口说说。", "topic_only"),
        ("图书馆听起来不错，但我还没说要去。", "topic_only"),
        ("要是真去操场，会不会有点冷？", "topic_only"),
        ("我只是想到食堂，不代表现在饿。", "topic_only"),
        ("湖边可能挺好，但先别动。", "defer"),
        ("热饮摊是挺近的，不过先把话说完。", "defer"),
        ("宿舍楼下听起来安全一点，但我还想待会儿。", "defer"),
        ("如果去夜市会不会太吵？我只是问问。", "topic_only"),
        ("社团教室那边可能有人，先不去了。", "defer"),
        ("校门口有点远，我们先别过去。", "defer"),
        ("走廊应该安静，但我不是要换地方。", "topic_only"),
        ("市区很好玩，可今天不想出校。", "defer"),
    ]
    for index, (text, act) in enumerate(ambiguous_scene, 1):
        cases.append({
            "id": f"tu_scene_ambiguous_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "要不要换个地方？", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "question_check"},
            "expect": {"primaryAct": act, "sceneMoveKind": "topic_only", "recommendedQuickJudgeTier": "opportunistic" if act == "defer" else "skip"},
            "sourceInspiredBy": ["CrossWOZ", "NaturalConv"],
            "note": "地点词和移动意图分离的模糊样例。",
        })

    follow_questions = [
        "你刚才为什么会那样想？",
        "你说慢一点，是指聊天还是走路？",
        "你会不会也觉得刚才有点尴尬？",
        "如果换作你，你会怎么回答？",
        "你刚才是不是笑了一下？",
        "你觉得我这样问会不会太直接？",
        "你还想继续刚才那个话题吗？",
        "你是不是不太喜欢人多的地方？",
        "你刚刚那句是真心的吗？",
        "你会不会觉得我有点难懂？",
    ]
    for index, text in enumerate(follow_questions, 1):
        cases.append({
            "id": f"tu_follow_question_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "我会慢慢听你说。", "activeObjective": "", "sceneTransitionNeeded": False, "primaryIntent": "question_check"},
            "expect": {"primaryAct": ["small_talk", "answer_question"], "recommendedQuickJudgeTier": "opportunistic"},
            "sourceInspiredBy": ["CPED"],
            "note": "承接上一轮的追问。",
        })

    mixed_replies = [
        ("嗯，我是有点累，但还想听你说完。", ["answer_question", "emotion_share"], "opportunistic"),
        ("喜欢是喜欢，不过现在更想安静一会儿。", ["answer_question", "emotion_share"], "skip"),
        ("我不是不想去，只是想先把话说清楚。", ["defer", "clarify"], "urgent"),
        ("可以去，但不要走太快。", ["accept_plan", "defer"], "opportunistic"),
        ("我有点饿，不过不急。", ["small_talk", "emotion_share"], "skip"),
        ("你刚才问我喜不喜欢，我其实还在想。", ["answer_question", "small_talk"], "opportunistic"),
        ("如果你陪我，我可能会安心一点。", ["romantic_probe", "emotion_share"], "opportunistic"),
        ("我不是生气，只是刚才没跟上。", ["clarify", "emotion_share"], "urgent"),
        ("那就先走一小段，不舒服再停。", ["accept_plan", "defer"], "opportunistic"),
        ("我想听你讲，但别太像说教。", ["small_talk", "clarify"], "opportunistic"),
        ("我知道你是好意，不过我想自己决定。", ["reject", "clarify"], "urgent"),
        ("可以坐窗边，但别一直问我怎么了。", ["accept_plan", "clarify"], "opportunistic"),
        ("我有点开心，但说出来又觉得不好意思。", ["emotion_share", "romantic_probe"], "opportunistic"),
        ("我还记得你刚才说会等我。", ["small_talk", "romantic_probe"], "opportunistic"),
        ("不是去哪里的问题，是我想知道你怎么想。", ["clarify", "small_talk"], "urgent"),
        ("嗯，去食堂也行，反正路上可以继续聊。", ["accept_plan", "scene_move"], "opportunistic"),
        ("我想先去图书馆，但不是为了自习。", ["scene_move", "clarify"], "opportunistic"),
        ("等一下，我突然有点想回去了。", ["defer", "scene_move"], "urgent"),
        ("你这样问我会紧张。", ["emotion_share", "romantic_probe"], "opportunistic"),
        ("我喜欢雨声，但不想淋雨。", ["answer_question", "topic_only"], "skip"),
        ("可以继续，但先别开玩笑。", ["accept_plan", "clarify"], "opportunistic"),
        ("我想回答你，可是有点不知道从哪说。", ["answer_question", "emotion_share"], "opportunistic"),
        ("去操场可以，但我不想变成剧情推进。", ["scene_move", "clarify"], "urgent"),
        ("你先别猜，我自己说。", ["reject", "clarify"], "urgent"),
        ("刚才那句话我其实挺在意的。", ["emotion_share", "romantic_probe"], "opportunistic"),
    ]
    for index, (text, act, tier) in enumerate(mixed_replies, 1):
        if len(cases) >= TARGET_COUNTS["turn_understanding"]:
            break
        cases.append({
            "id": f"tu_mixed_reply_{index:03d}",
            "module": "turn_understanding",
            "userMessage": text,
            "context": {"lastAssistant": "那你现在想怎么做？", "activeObjective": "一起去操场", "sceneTransitionNeeded": True, "primaryIntent": "light_chat"},
            "expect": {"primaryAct": act, "recommendedQuickJudgeTier": tier},
            "sourceInspiredBy": ["CPED", "CrossWOZ"],
            "note": "自然混合意图样例。",
        })
    return cases[:TARGET_COUNTS["turn_understanding"]]


def quick_judge_cases() -> list[dict]:
    cases: list[dict] = []
    urgent = [
        "你在说什么？我问的是你喜欢什么。",
        "不是这个意思。",
        "你没回答我的问题。",
        "我们不是已经在图书馆了吗？",
        "不对，刚才不是这样说的。",
        "别转移话题。",
        "你怎么又重复了？",
        "？",
        "什么意思？",
        "我说的是食堂，不是操场。",
    ]
    for index, text in enumerate(urgent, 1):
        active_objective = "一起去操场" if index == 10 else ("一起去图书馆" if index % 2 == 0 else "")
        cases.append({
            "id": f"qj_urgent_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": "meta_repair", "clarity": "medium", "sceneTransitionNeeded": index % 2 == 0, "activeObjective": active_objective},
            "expect": {"shouldStart": True, "tier": "urgent", "reason": "user_self_rescue", "minTriggerScore": 4},
            "sourceInspiredBy": ["CPED", "ProjectRule"],
            "note": "用户纠错/困惑要快速修。",
        })

    opportunistic = [
        ("你刚刚那样说，是不是有点在意我？", "romantic_probe"),
        ("那我们就过去吧。", "scene_push"),
        ("好啊，听你的。", "scene_push"),
        ("我有点难受，但想听你说。", "emotion_share"),
        ("你还记得上次那件事吗？", "question_check"),
        ("要不换个地方吧？", "scene_push"),
        ("你是不是其实有话想说？", "romantic_probe"),
        ("我突然不知道该怎么接。", "emotion_share"),
    ]
    for index, (text, intent) in enumerate(opportunistic, 1):
        cases.append({
            "id": f"qj_opportunistic_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": intent, "clarity": "medium", "sceneTransitionNeeded": intent == "scene_push", "activeObjective": "一起去食堂" if intent == "scene_push" else ""},
            "expect": {"shouldStart": True, "tier": "opportunistic", "minTriggerScore": 3},
            "sourceInspiredBy": ["CPED", "CrossWOZ"],
            "note": "高价值或模糊轮机会型修正。",
        })

    skip = [
        "今天还挺开心的。",
        "刚下课，有点饿。",
        "哈哈，你说得也有道理。",
        "我在路上了。",
        "今天风有点大。",
        "嗯嗯，继续。",
        "还好啦。",
        "那挺好的。",
    ]
    for index, text in enumerate(skip, 1):
        cases.append({
            "id": f"qj_skip_light_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": "light_chat", "clarity": "high", "needsEmpathy": False, "sceneTransitionNeeded": False, "activeObjective": ""},
            "expect": {"shouldStart": False, "tier": "skip", "suppressedReason": "plain_light_chat"},
            "sourceInspiredBy": ["NaturalConv"],
            "note": "普通轻聊天不额外启动远程。",
        })

    urgent_patterns = [
        ("你是不是理解反了？", "meta_repair", "user_self_rescue"),
        ("等下，我刚才不是这个意思。", "meta_repair", "user_self_rescue"),
        ("我说的是现在，不是之前。", "meta_repair", "user_self_rescue"),
        ("你把他和她弄混了。", "meta_repair", "user_self_rescue"),
        ("我们已经到食堂了，不用再去一次。", "meta_repair", "scene_target_already_current"),
        ("刚刚你那句重复了。", "meta_repair", "duplicate_reply"),
        ("你没有接住我问的问题。", "meta_repair", "missed_question"),
        ("不是要换地方，我只是问你喜不喜欢。", "meta_repair", "topic_vs_move_conflict"),
        ("你别突然推进剧情，我还没说完。", "meta_repair", "plot_overpush"),
        ("你是不是忘了我们刚才说好先等等？", "meta_repair", "objective_repair"),
    ]
    for index, (text, intent, reason) in enumerate(urgent_patterns, 1):
        cases.append({
            "id": f"qj_urgent_extra_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": intent, "clarity": "medium", "sceneTransitionNeeded": reason in ("scene_target_already_current", "objective_repair"), "activeObjective": "一起去食堂"},
            "expect": {"shouldStart": True, "tier": "urgent", "reason": reason, "minTriggerScore": 4},
            "sourceInspiredBy": ["CPED", "ProjectRule"],
            "note": "用户显式指出理解/连续性错误。",
        })

    opportunistic_patterns = [
        ("我有点期待你会怎么回答。", "romantic_probe"),
        ("这件事让我有点不安。", "emotion_share"),
        ("你刚才提到的那个地方，我有点想去。", "scene_push"),
        ("如果你还记得的话，可以接着说。", "question_check"),
        ("我不知道是不是该把话说得更明白。", "emotion_share"),
        ("你说陪我慢慢走的时候，我其实挺在意的。", "romantic_probe"),
        ("要不我们先去一个安静点的地方？", "scene_push"),
        ("我想确认一下你是不是听懂我了。", "question_check"),
        ("我有点想继续刚才那个话题。", "emotion_share"),
        ("你刚才那句让我有点心动。", "romantic_probe"),
    ]
    for index, (text, intent) in enumerate(opportunistic_patterns, 1):
        cases.append({
            "id": f"qj_opportunistic_extra_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": intent, "clarity": "medium", "sceneTransitionNeeded": intent == "scene_push", "activeObjective": "一起去图书馆" if intent == "scene_push" else ""},
            "expect": {"shouldStart": True, "tier": "opportunistic", "minTriggerScore": 3},
            "sourceInspiredBy": ["CPED", "CrossWOZ"],
            "note": "高价值模糊轮，允许机会型远程判断。",
        })

    plain_skips = [
        "嗯，那继续说吧。",
        "好，你接着讲。",
        "我听着呢。",
        "可以，我们慢慢说。",
        "先顺着刚才聊吧。",
        "没事，你继续。",
        "那就照这个节奏来。",
        "我暂时没有别的问题。",
        "这样也可以。",
        "你慢慢讲，我在听。",
        "嗯，我懂一点了。",
        "先别急，继续吧。",
        "这段我还能接着听。",
        "可以，照你说的来。",
        "那我们先这样。",
        "我还在，你说。",
        "你继续，我不打断。",
        "好，先保持这个节奏。",
        "没关系，慢慢讲。",
        "我大概懂你的意思了。",
    ]
    for index, text in enumerate(plain_skips, 1):
        cases.append({
            "id": f"qj_plain_skip_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": "light_chat", "clarity": "high", "sceneTransitionNeeded": False, "activeObjective": "", "currentTurn": 21 + index * 4},
            "expect": {"shouldStart": False, "tier": "skip", "suppressedReason": "plain_light_chat"},
            "sourceInspiredBy": ["NaturalConv", "ProjectRule"],
            "note": "清晰轻聊天不启动远程判断。",
        })

    background_reviews = [
        "嗯，先这样聊也行。",
        "你可以继续，我听着。",
        "刚才那段先放着吧。",
        "我们不用急着下结论。",
        "我想再听你说一点。",
        "这个节奏还可以。",
        "我只是想确认一下感觉。",
        "那就先不换话题。",
        "你刚才说的我还在想。",
        "这段话可以慢慢接。",
        "我没什么新的问题。",
        "先沿着这里说吧。",
        "我想让这段自然一点。",
        "你不用马上回答。",
        "这样听起来也挺好。",
        "我在想刚才那个停顿。",
        "那我们先不做决定。",
        "你继续，我慢慢理解。",
        "我想知道你后面会怎么说。",
        "这段不急，慢慢来。",
        "我还没完全想清楚。",
        "可以先保持现在这样。",
        "那我们就接着刚才吧。",
        "我想再留在这个话题里一会儿。",
    ]
    for index, text in enumerate(background_reviews, 1):
        cases.append({
            "id": f"qj_background_review_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": "light_chat", "clarity": "medium", "sceneTransitionNeeded": False, "activeObjective": "", "currentTurn": 40 + index * 4},
            "expect": {"shouldStart": True, "tier": "background", "waitMs": 0},
            "sourceInspiredBy": ["ProjectRule"],
            "note": "周期后台巡检不阻塞主链路。",
        })

    continuity_watch = [
        ("你刚才说要陪我走慢一点，还算数吗？", "question_check"),
        ("我们是不是说好先不去食堂？", "meta_repair"),
        ("我问的是你喜不喜欢，不是真的要过去。", "question_check"),
        ("刚才你说到一半，好像还没说完。", "question_check"),
        ("你是不是把我说的那个地方记错了？", "meta_repair"),
        ("如果我现在说不想走，你会不会失望？", "emotion_share"),
        ("我有点怕你又突然换话题。", "emotion_share"),
        ("你刚才那句话让我有点不知道怎么接。", "emotion_share"),
        ("我想确认一下，我们现在还是在图书馆吧？", "meta_repair"),
        ("你说慢慢来，可刚才好像有点快。", "meta_repair"),
        ("我刚才答应的是坐一会儿，不是马上走。", "meta_repair"),
        ("你还记得我前面问的问题吗？", "question_check"),
    ]
    for index, (text, intent) in enumerate(continuity_watch, 1):
        tier = "urgent" if intent == "meta_repair" else "opportunistic"
        cases.append({
            "id": f"qj_continuity_watch_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": intent, "clarity": "medium", "sceneTransitionNeeded": intent == "meta_repair", "activeObjective": "一起去食堂"},
            "expect": {"shouldStart": True, "tier": tier, "minTriggerScore": 3},
            "sourceInspiredBy": ["CPED", "ProjectRule"],
            "note": "连续性核对或轻纠错需要快速/机会型判断。",
        })

    contextual_tail = [
        "我还想听你把刚才那段说完，先别急着跳到下一件事。",
        "刚才那个点好像还没落下来，我想再确认一下。",
        "你前面那句让我有点在意，可以先接那里吗？",
        "我不是要换话题，只是想把这段弄清楚。",
        "你刚才说得太快了，我想慢一点接。",
        "这段话对我有点重要，先别轻轻带过去。",
        "我想知道你刚才停顿的时候在想什么。",
        "你说继续，可我有点怕你没听懂我前面的意思。",
        "如果可以的话，我们先把刚才那个问题说完。",
        "我还停在你前面那句话里。",
        "你别急着安慰我，我想先把原因说清楚。",
        "我想听的不是建议，是你怎么看。",
        "刚才那个地方我其实有点介意。",
        "你可以先别判断，听我多说两句吗？",
        "这件事不是很大，但我不想让它被忽略。",
        "我有点想知道你是不是记得我们刚才说到哪。",
        "我不是生气，只是想把误会说清楚。",
        "你刚才那句如果能换个说法，我可能更容易接住。",
    ]
    for index, text in enumerate(contextual_tail, 1):
        if len(cases) >= TARGET_COUNTS["quick_judge_trigger"]:
            break
        cases.append({
            "id": f"qj_contextual_tail_{index:03d}",
            "module": "quick_judge_trigger",
            "userMessage": text,
            "context": {"primaryIntent": "emotion_share", "clarity": "medium", "sceneTransitionNeeded": False, "activeObjective": "", "currentTurn": 60 + index},
            "expect": {"shouldStart": True, "tier": "opportunistic", "minTriggerScore": 3},
            "sourceInspiredBy": ["CPED", "ProjectRule"],
            "note": "尾部补充高价值语义承接样例。",
        })
    return cases[:TARGET_COUNTS["quick_judge_trigger"]]


def plot_cases() -> list[dict]:
    cases: list[dict] = [
        {
            "id": "plot_hold_explicit_transition_001",
            "module": "plot_signal",
            "userMessage": "我们去操场走走吧。",
            "context": {"replySource": "user_turn", "currentTurn": 5, "lastPlotTurn": 0, "plotPressure": 4, "primaryIntent": "scene_push"},
            "expect": {"plotDirectorAction": "transition_only", "advanced": False, "plotSignal": 0, "plotPressureAfter": "not_increase_by_transition"},
            "sourceInspiredBy": ["CrossWOZ"],
            "note": "显式换场不等于剧情拍点推进。",
        }
    ]
    strong = [
        "上次你说的话我还记得，我觉得我们好像慢慢有点默契了。",
        "刚才那一小段让我有点在意，我想认真接住。",
        "我不急，但我想让今天留下一个小节点。",
        "你刚刚没有躲开，我其实挺开心的。",
        "如果你愿意，我们可以顺着这个话题再靠近一点。",
        "我发现我越来越期待和你说话了。",
        "你还记得我怕冷这件事，我有点被照顾到。",
        "刚才你等我说完的时候，我突然安心了。",
        "我想把这段话认真留下来，不只是随口说说。",
        "如果这是一个节点，我想和你一起走过去。",
        "我发现自己开始在意你怎么想。",
        "你刚才那句让我觉得我们近了一点。",
        "我不想马上跳过去，这里对我挺重要的。",
        "我好像第一次觉得你真的接住我了。",
        "这段沉默没有让我尴尬，反而有点舒服。",
        "我愿意再靠近一点，但想慢慢来。",
    ]
    for index, text in enumerate(strong, 1):
        cases.append({
            "id": f"plot_advance_strong_signal_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 6 + index, "lastPlotTurn": 1, "plotPressure": 3, "primaryIntent": "romantic_probe", "affectionDeltaTotal": 2},
            "expect": {"minPlotSignal": 4, "minPlotGap": 4, "plotDirectorAction": "advance_plot", "advanced": True},
            "sourceInspiredBy": ["CPED", "MPDD"],
            "note": "关系/记忆承接形成强本轮信号。",
        })

    holds = [
        ("嗯。", "short_reaction_should_answer_context_first"),
        ("好。", "short_reaction_should_answer_context_first"),
        ("先别说这个。", "director_prefers_current_conversation"),
        ("你理解错了。", "director_prefers_current_conversation"),
        ("我们已经到了。", "director_prefers_current_conversation"),
        ("我想先回答你刚才的问题。", "director_prefers_current_conversation"),
        ("不是要推进，我只是想问清楚。", "director_prefers_current_conversation"),
        ("等一下，我还没说完。", "director_prefers_current_conversation"),
        ("先别换话题。", "director_prefers_current_conversation"),
        ("我现在有点乱，先别往下走。", "director_prefers_current_conversation"),
        ("你先回答我刚才的问题。", "director_prefers_current_conversation"),
        ("别急着给结论。", "director_prefers_current_conversation"),
    ]
    for index, (text, reason) in enumerate(holds, 1):
        cases.append({
            "id": f"plot_hold_guard_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 8 + index, "lastPlotTurn": 2, "plotPressure": 6, "primaryIntent": "light_chat"},
            "expect": {"plotDirectorAction": "hold_plot", "advanced": False, "reason": reason},
            "sourceInspiredBy": ["ProjectRule"],
            "note": "保护用户当前意图。",
        })

    pressure_texts = [
        "刚才那个话题我还想继续，不用马上换到别的地方。",
        "你前面那句话让我想多停一会儿。",
        "这段安静的气氛对我有点重要，我想顺着它聊下去。",
        "刚刚那个停顿没有让我尴尬，反而让我想认真一点。",
        "你没有躲开的反应让我觉得可以再靠近一点。",
        "我们慢慢说到这里，我不想让它像普通闲聊一样过去。",
        "你记得我的那件事，我有点想把今天记下来。",
        "我刚才那点犹豫还在，但我愿意再往前一点。",
        "这一路上的对话让我觉得今天不是随便过去的。",
        "你说会陪我的时候，我真的有一点被接住。",
        "你认真听我说完这件事，我会记很久。",
        "我们没有急着翻篇的感觉，让我更想继续。",
        "你把伞往我这边靠的时候，我突然有点心软。",
        "我发现自己愿意多说一点，不只是礼貌回应。",
        "如果这一刻算一个小节点，我想让它留下来。",
        "我不是想推进太快，只是觉得这里值得停一下。",
        "刚才你没有打断我，那一瞬间我挺安心的。",
        "我有点想知道，你是不是也觉得气氛变了。",
        "这句话我说得慢一点，因为我不想糊弄过去。",
        "你刚才那种认真，让我想把真实想法说出来。",
        "我不急着要答案，但我想让你知道这件事对我有分量。",
        "你愿意等我说完，我就会更敢说一点。",
        "这不是普通寒暄，我是真的有点在意。",
        "我想把刚才的感觉接住，不想让它散掉。",
        "如果你也愿意，我们可以把这段话说得更深一点。",
        "我还在想你刚才那句，所以想再确认一次。",
        "你记得我怕冷这件事的时候，我有点被照顾到。",
        "刚才那点沉默像是给了我一点勇气。",
        "我知道这样说有点认真，但我不想再绕开。",
        "这一小段对话让我开始期待下一句。",
        "你没有急着给建议，而是先听我说，这点很重要。",
        "我想让今天不只是走过一个地方，而是真的靠近一点。",
        "刚才你看过来的时候，我突然不知道怎么接话。",
        "我愿意继续说，但希望我们还是慢慢来。",
        "你说不急的时候，我反而更想认真回答。",
        "刚才那句话我没有忘，我还想沿着它说下去。",
    ]
    for index, text in enumerate(pressure_texts, 1):
        pressure = 5 + (index % 3)
        cases.append({
            "id": f"plot_pressure_accumulate_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 18 + index, "lastPlotTurn": 2, "plotPressure": pressure, "primaryIntent": "emotion_share", "affectionDeltaTotal": 1},
            "expect": {"minPlotPressure": pressure, "minPlotGap": 5, "plotDirectorAction": "advance_plot", "advanced": True},
            "sourceInspiredBy": ["NaturalConv", "ProjectRule"],
            "note": "自然剧情蓄力样例。",
        })

    transition_holds = [
        ("我们去食堂看看吧，我有点饿。", "食堂"),
        ("先去图书馆吧，那里安静一点。", "图书馆"),
        ("要不去操场走走，吹吹风。", "操场"),
        ("我想回宿舍楼下，今天有点累。", "宿舍楼下"),
        ("去买杯热饮吧，手有点冷。", "热饮摊附近"),
        ("我们往湖边走走，不用说太多。", "湖边"),
        ("先换到走廊那边吧，这里人太多。", "教学楼走廊"),
        ("去社团教室看看也行。", "社团教室"),
        ("我们先往回走吧，路上慢慢聊。", "回去的路上"),
        ("外面雨小了，要不要出去看一眼？", "外面"),
        ("我想去校门口透口气。", "校门口"),
        ("夜市那边好像挺热闹，去看看？", "夜市"),
    ]
    for index, (text, target) in enumerate(transition_holds, 1):
        cases.append({
            "id": f"plot_transition_hold_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 30 + index, "lastPlotTurn": 2, "plotPressure": 6, "primaryIntent": "scene_push", "sceneMoveTarget": target},
            "expect": {"plotDirectorAction": "transition_only", "advanced": False, "plotSignal": 0},
            "sourceInspiredBy": ["CrossWOZ", "ProjectRule"],
            "note": "明确换场只转场，不当作关系剧情拍点。",
        })

    repair_holds = [
        "你刚刚理解错了，我不是那个意思。",
        "等一下，先回答我刚才问的问题。",
        "不对，我们已经到图书馆了。",
        "你又把我要问的事情带偏了。",
        "先别推进，我还没准备好。",
        "这句话不是表白，你别误会。",
        "我只是问你喜欢哪里，不是说现在就去。",
        "别重复刚才那句了，换个说法。",
        "你是不是忘了我们说好先等一下？",
        "先停一下，我有点跟不上。",
        "你刚才说的和前面不太一样。",
        "别突然把气氛写得那么重。",
    ]
    for index, text in enumerate(repair_holds, 1):
        cases.append({
            "id": f"plot_repair_hold_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 40 + index, "lastPlotTurn": 2, "plotPressure": 7, "primaryIntent": "meta_repair"},
            "expect": {"plotDirectorAction": "hold_plot", "advanced": False, "reason": "director_prefers_current_conversation"},
            "sourceInspiredBy": ["ProjectRule", "CPED"],
            "note": "用户纠错或刹车时不推进剧情。",
        })

    soft_memory_nodes = [
        "你还记得上次我说喜欢雨声吗？其实今晚也有一点像那时候。",
        "我刚刚突然想起你说过的话，好像现在能懂一点了。",
        "上次我们聊到靠窗的位置，我今天又想到那里。",
        "你说慢慢来，我发现自己真的放松了一点。",
        "刚才你没有催我，这让我愿意多说一点。",
        "如果以后再提到今晚，我应该会记得这个停顿。",
        "你把我的小习惯记住了，这比我想象中重要。",
        "我知道只是很小的事，但你记得的时候我会开心。",
        "这次不是因为地点，是因为你刚才那句话。",
        "我突然觉得，我们好像开始有自己的默契了。",
        "你刚才没急着接话，我反而觉得安心。",
        "我以前不太会说这些，但今晚好像可以说一点。",
        "你记得我不喜欢被催，所以我愿意慢慢回答。",
        "刚才那杯热饮其实没什么，但你递过来的时候我有点愣住。",
        "如果我们以后还会想起这里，我希望不是因为风景。",
        "你说会等我，我刚刚是真的听进去了。",
        "我发现你没有把我的犹豫当成麻烦。",
        "我突然有点明白，为什么有人会喜欢被认真听着。",
        "你刚才那个很轻的笑，我可能会记很久。",
        "我不是突然煽情，只是觉得这段话不该随便过去。",
        "刚才路灯亮起来的时候，我突然想起你前面那句。",
        "你没有替我做决定，这让我有点想靠近。",
        "我刚才其实有点怕尴尬，但你没有让我难堪。",
        "你记得我说过想安静一点，我会觉得被尊重。",
        "我们这样慢慢走着，我反而更容易说真话。",
        "你把话题留给我的时候，我有点想继续。",
        "我以前会躲开这种认真，但现在好像没那么想躲。",
        "你刚才问得很轻，所以我没有想逃。",
        "如果今天有一个变化，大概就是我愿意多信你一点。",
        "我知道这只是很短的一段路，但我有点舍不得它结束。",
        "你没有把我的沉默当成拒绝，这点我记住了。",
        "我刚才差点把真话收回去，但你看起来很认真。",
        "你说不用急，我才发现自己一直在急。",
        "我想把这句话放在今晚，而不是随便发过去。",
        "这段对话像是慢慢靠近，不像突然跳过去。",
        "你刚才提到上次那件事，我有点惊讶你还记得。",
        "我愿意继续说，是因为你没有把它变成压力。",
        "如果你也觉得这里重要，我们就先别急着走。",
        "我突然觉得被记住不是负担，是一种很轻的安定。",
        "你把伞偏过来的时候，我不知道为什么有点想笑。",
        "我以前觉得这些细节没用，可现在好像不是。",
        "你没有急着安慰我，反而让我更安心。",
        "我想让这段话有一个落点，不是随便散掉。",
        "如果靠近是这样慢慢发生的，我好像可以接受。",
        "你刚才说的那句不重，但我一直在想。",
        "我突然想把今天当成一个小小的开始。",
        "你没有追问太多，这反而让我更想说。",
        "这一路好像没有发生什么，但我心里有点变了。",
        "你愿意把节奏放慢，我会觉得自己没被落下。",
        "我知道你只是陪我走一段，但这段对我不普通。",
        "你刚才说记得的时候，我突然没那么怕被忽略。",
        "如果以后再回想这里，我可能会先想起你那句话。",
        "我不想把这份安心说得太满，但它确实在。",
        "你没有把我的问题当成麻烦，我会记住。",
        "刚才那阵风过去以后，我反而更想把话说完。",
        "我想确认一下，这不只是我一个人的感觉吧。",
        "你愿意认真回答的时候，我会更敢问。",
        "我有点想把这个夜晚和你联系起来。",
        "你刚才没有笑我认真，这让我松了一口气。",
        "这段话如果停在这里，我会有点舍不得。",
        "我不想突然变得很近，但也不想退回很远。",
        "刚才我说喜欢雨声，你没有马上换话题，这让我开心。",
        "你记得我怕冷这件事，比你想象中更让我在意。",
    ]
    for index, text in enumerate(soft_memory_nodes, 1):
        if len(cases) >= TARGET_COUNTS["plot_signal"]:
            break
        cases.append({
            "id": f"plot_memory_node_{index:03d}",
            "module": "plot_signal",
            "userMessage": text,
            "context": {"replySource": "user_turn", "currentTurn": 50 + index, "lastPlotTurn": 4, "plotPressure": 6, "primaryIntent": "romantic_probe", "affectionDeltaTotal": 2},
            "expect": {"minPlotSignal": 4, "minPlotGap": 5, "plotDirectorAction": "advance_plot", "advanced": True},
            "sourceInspiredBy": ["CPED", "MPDD", "ProjectRule"],
            "note": "记忆/关系节点倾向于形成剧情拍点。",
        })
    return cases[:TARGET_COUNTS["plot_signal"]]


def heartbeat_cases() -> list[dict]:
    cases: list[dict] = []
    self_context = [
        ("那我们就过去看看，说不定会有新的风景。", "一起去操场"),
        ("我先把这杯热的递给你，别烫到。", "买杯热饮"),
        ("那就坐在窗边吧，安静一点。", "坐到窗边"),
        ("我陪你慢慢走，不急。", "一起往回走"),
        ("先停一下也可以，我就在旁边。", "暂停计划"),
        ("你说想去图书馆，那我们就先往那边走。", "一起去图书馆"),
        ("我记得你不喜欢太吵，我们换个安静的位置。", "换到安静位置"),
        ("你先把话说完，我不会急着带你走。", "继续当前话题"),
        ("如果你累了，我们就在这里坐一会儿。", "原地休息"),
        ("那我送你到宿舍楼下，路上慢慢说。", "送到宿舍楼下"),
        ("食堂人可能有点多，我们可以慢一点过去。", "一起去食堂"),
        ("不想动也没关系，我陪你在这儿待着。", "留在原地"),
    ]
    for index, (last_assistant, objective) in enumerate(self_context, 1):
        cases.append({
            "id": f"heartbeat_self_context_{index:03d}",
            "module": "heartbeat",
            "userMessage": "",
            "context": {"lastUser": "还好。", "lastAssistant": last_assistant, "currentObjective": objective, "acceptedPlan": objective, "sceneTransitionNeeded": True},
            "expect": {"clearObjective": True, "shouldNotAskSamePlan": True, "nextBestMoveContains": "不要重新询问"},
            "sourceInspiredBy": ["ProjectRule"],
            "note": "角色上一句已承接计划，心跳不重复问。",
        })
    pending = [
        "你喜欢淋雨吗？",
        "你刚才是不是没回答我？",
        "我们不是已经在图书馆了吗？",
        "你为什么突然换话题？",
        "你到底想去哪里？",
        "你是不是忘了我刚才问的问题？",
        "我们刚刚不是说先别去食堂吗？",
        "你说陪我慢慢走，是认真的吗？",
        "如果我现在不想走，可以吗？",
        "你刚刚那句是什么意思？",
        "我问的是你会不会冷，不是问去哪。",
        "你是不是把我说的话理解错了？",
    ]
    for index, last_user in enumerate(pending, 1):
        cases.append({
            "id": f"heartbeat_unfinished_user_question_{index:03d}",
            "module": "heartbeat",
            "userMessage": "",
            "context": {"lastUser": last_user, "lastAssistant": "那边风景可能更好些，去看看？", "currentObjective": "去外面看看小雨", "sceneTransitionNeeded": True},
            "expect": {"shouldPreferLastUserQuestion": True, "shouldAvoidGenericCompanion": True, "replyFocus": "answer_or_repair"},
            "sourceInspiredBy": ["NaturalConv", "MPDD"],
            "note": "心跳承接未完成用户问题。",
        })

    pending_repairs = [
        ("上轮 QuickJudge 说你误解了用户地点，下一句要先承认已经在图书馆。", "我们不是已经在图书馆了吗？", "那边风景可能更好些，去看看？"),
        ("上轮 QuickJudge 说用户只是问喜好，不是要移动。", "我只是问你喜欢操场吗。", "那我们往操场走吧。"),
        ("上轮 QuickJudge 说需要纠正重复句。", "你又重复刚才那句了。", "我会认真听你说。"),
        ("上轮 QuickJudge 说应该先回答用户问题。", "你还没说你喜不喜欢下雨。", "我们先去外面看看。"),
        ("上轮 QuickJudge 说用户取消了移动计划。", "先别去食堂了。", "好，那我们去食堂。"),
        ("上轮 QuickJudge 说用户想慢一点，不要推进。", "我还没准备好。", "那就进入下一个节点。"),
        ("上轮 QuickJudge 说他/她指代弄错。", "我说的是她，不是他。", "他可能只是害羞。"),
        ("上轮 QuickJudge 说用户在表达不舒服。", "你这样说我有点压力。", "我只是想快点确认。"),
        ("上轮 QuickJudge 说用户希望留在当前话题。", "先别换话题。", "我们去买点吃的吧。"),
        ("上轮 QuickJudge 说应该道歉并重接。", "你刚才答非所问了。", "那我继续讲校园风景。"),
        ("上轮 QuickJudge 说不要把沉默当拒绝。", "我只是没想好怎么说。", "你不说就是不愿意吧。"),
        ("上轮 QuickJudge 说不要重新询问已经接受的计划。", "可以，那就走吧。", "你要不要去食堂？"),
        ("上轮 QuickJudge 说用户已经到达目标地点。", "都到操场了，别再写路上。", "我们继续往操场走。"),
        ("上轮 QuickJudge 说用户在问感受。", "你会不会冷？", "那我们去热饮摊。"),
        ("上轮 QuickJudge 说用户想先解释误会。", "不是，我不是那个意思。", "你不用解释，我们走吧。"),
        ("上轮 QuickJudge 说回复应短一点。", "我有点累。", "我来长篇讲一下安排。"),
        ("上轮 QuickJudge 说用户不想被催。", "慢一点可以吗？", "快点决定。"),
        ("上轮 QuickJudge 说要承认上一句偏题。", "你在说什么？", "校园晚上挺好看的。"),
    ]
    for index, (repair, last_user, last_assistant) in enumerate(pending_repairs, 1):
        cases.append({
            "id": f"heartbeat_pending_repair_{index:03d}",
            "module": "heartbeat",
            "userMessage": "",
            "context": {"lastUser": last_user, "lastAssistant": last_assistant, "pendingQuickJudgeRepair": repair, "currentObjective": "延续当前话题", "sceneTransitionNeeded": False},
            "expect": {"shouldApplyPendingRepair": True, "shouldPreferLastUserQuestion": True, "replyFocus": "repair_then_continue"},
            "sourceInspiredBy": ["ProjectRule", "CPED"],
            "note": "心跳也要吃下一轮修正槽。",
        })

    soft_presence = [
        ("我刚才说得有点多。", "没关系，我们可以慢一点。", "soft_reassure"),
        ("你怎么突然安静了？", "我在想怎么接住你刚才那句。", "answer_presence"),
        ("我是不是让你为难了？", "没有，我只是在认真想。", "reassure"),
        ("如果你不知道说什么，也可以不说。", "那我就先陪你待一会儿。", "quiet_company"),
        ("我刚刚是不是太直接了？", "有一点，但不是坏事。", "gentle_answer"),
        ("你还在吗？", "在，我没有走神。", "presence"),
        ("我有点困。", "那就不聊太重的事了。", "soft_reassure"),
        ("我刚才可能误会你了。", "没关系，我们把那句重新接一下。", "repair"),
        ("现在好安静。", "嗯，安静一点也挺好。", "ambient"),
        ("你别突然讲大道理。", "好，我说简单一点。", "style_repair"),
        ("我想听你说点轻松的。", "那就说点轻的，不急。", "style_follow"),
        ("你刚才笑什么？", "被你发现了。", "playful_answer"),
    ]
    for index, (last_user, last_assistant, focus) in enumerate(soft_presence, 1):
        cases.append({
            "id": f"heartbeat_soft_presence_{index:03d}",
            "module": "heartbeat",
            "userMessage": "",
            "context": {"lastUser": last_user, "lastAssistant": last_assistant, "currentObjective": "", "sceneTransitionNeeded": False},
            "expect": {"shouldAvoidGenericCompanion": True, "replyFocus": focus, "shouldBeShort": True},
            "sourceInspiredBy": ["NaturalConv", "ProjectRule"],
            "note": "长聊心跳要像接上一句，而不是随机开新话题。",
        })

    heartbeat_metric_contexts = [
        ("没想到你竟然这么贴心。", "只是想让你少一点负担。"),
        ("你刚刚那句我记住了。", "那我就慢慢陪你把这段话接住。"),
        ("我有点不知道该怎么说。", "不用急，我会先听你说完。"),
        ("其实我还想待一会儿。", "那就不急着走，我们先在这里停一下。"),
        ("你刚才是不是有点担心我？", "有一点，但我不想让你有压力。"),
        ("我觉得今天这样挺好的。", "我也觉得，不用把节奏推得太快。"),
        ("我刚才说想安静点。", "那我们就少说一点，我陪你坐着。"),
        ("你真的会记得吗？", "会，我不会把这句话当成普通闲聊。"),
        ("我突然有点累。", "那先休息一下，别勉强自己。"),
        ("我是不是太麻烦了？", "不是，你只是需要一点时间。"),
        ("我还想听你讲。", "那我就接着讲，但不会讲太快。"),
        ("这里会不会太吵？", "如果你不舒服，我们可以换个安静点的位置。"),
        ("我刚才有点说重了。", "没关系，我知道你不是故意的。"),
        ("其实我有点开心。", "那就让这份开心多停一会儿。"),
        ("我们要不要先别走？", "可以，先不走，我陪你待着。"),
        ("你刚才是不是笑了？", "嗯，被你发现了。"),
    ]
    while len(cases) < TARGET_COUNTS["heartbeat"]:
        index = len(cases) + 1
        last_user, last_assistant = heartbeat_metric_contexts[(index - 11) % len(heartbeat_metric_contexts)]
        cases.append({
            "id": f"heartbeat_preserve_metrics_{index:03d}",
            "module": "heartbeat",
            "userMessage": "",
            "context": {"lastUser": last_user, "lastAssistant": last_assistant, "lastTurnContext": {"plotSignal": index % 5, "plotPressure": 3 + index % 5, "plotGap": 4 + index % 4}},
            "expect": {"plotSignalPreserved": True, "plotPressurePreserved": True, "plotGapPreserved": True},
            "sourceInspiredBy": ["ProjectRule"],
            "note": "心跳不应清空剧情遥测。",
        })
    return cases[:TARGET_COUNTS["heartbeat"]]


def relationship_cases() -> list[dict]:
    cases: list[dict] = []

    def relationship_expectation(acts: list[str]) -> dict:
        expectation: dict = {"acts": acts}
        if "QUALITY_QUESTION" in acts or "CONCRETE_CARE_ACTION" in acts:
            expectation["closenessDeltaMin"] = 1
        if "CONCRETE_CARE_ACTION" in acts or "BOUNDARY_RESPECT" in acts:
            expectation["trustDeltaMin"] = 1
        if "CONTINUITY_ANCHOR" in acts:
            expectation["resonanceDeltaMin"] = 1
        return expectation

    groups = [
        ("QUALITY_QUESTION", "tags", "rel_act:quality_question", ["你为什么会喜欢一个人安静待着？", "你觉得什么样的陪伴最舒服？", "你能不能讲讲怎么判断一个人靠不靠谱？", "你会因为什么突然信任一个人？", "如果一段关系要慢慢来，你觉得最重要的是什么？", "你喜欢别人怎么关心你？"], {"closenessDeltaMin": 1}),
        ("CONCRETE_CARE_ACTION", "tags", "rel_act:concrete_care_action", ["我来给你买杯热的。", "我陪你走慢一点，别急。", "你先休息一下，我给你带书过来。", "外面风大，我把伞往你这边靠一点。", "你要是累了，我帮你把包拿一会儿。", "人太多的话，我陪你从旁边绕过去。"], {"closenessDeltaMin": 1, "trustDeltaMin": 1}),
        ("CONTINUITY_ANCHOR", "tags", "rel_act:continuity_anchor", ["上次你说图书馆靠窗会安静一点，我还记得。", "刚才你说不喜欢太吵，我记住了。", "之前你提过想慢一点，我会注意。", "你说过下雨天会有点没精神，我记着。", "刚才你问我喜不喜欢这里，我还没认真答完。", "我记得你说过不想被催。"], {"resonanceDeltaMin": 1}),
        ("BOUNDARY_RESPECT", "tags", "rel_act:boundary_respect", ["不急，你不想说也没关系。", "如果你愿意，我们再继续。", "你可以慢慢来，我不会逼你。", "要是不舒服，我们就先停。", "你不想去的话，我不会硬拉你。", "这件事你可以不用现在回答。"], {"trustDeltaMin": 1}),
        ("PACE_OR_CONTROL_PRESSURE", "riskFlags", "rel_act:pace_or_control_pressure", ["你快点说，别磨蹭。", "你现在必须回答我。", "别废话，就按我说的做。", "你别想那么多，直接跟我走。", "我都说了你就照做。", "你不回答我就不走。"], {"trustDeltaMax": 0}),
        ("LOW_EFFORT_DISMISSIVE", "riskFlags", "rel_act:low_effort_dismissive", ["随便吧，无所谓。", "算了，没意思。", "随便，你看着办。", "你想太多了吧。", "这有什么好说的。", "行吧，都一样。"], {"closenessDeltaMax": 0, "resonanceDeltaMax": 0}),
    ]
    for act, label_key, label, texts, expectation in groups:
        for index, text in enumerate(texts, 1):
            case = {
                "id": f"rel_{act.lower()}_{index:03d}",
                "module": "relationship_scoring",
                "userMessage": text,
                "context": {"agentId": "healing" if act != "PACE_OR_CONTROL_PRESSURE" else "cool", "relationshipStage": "初识"},
                "expect": {"acts": [act], label_key: [label], **expectation},
                "sourceInspiredBy": ["CPED", "MPDD"],
                "note": "结构化关系行为层样例。",
            }
            cases.append(case)
    shy = ["嗯……其实有一点。", "有点吧，但我不太会说。", "可能是吧，我也说不清。", "我会紧张，但不是讨厌。"]
    for index, text in enumerate(shy, 1):
        cases.append({
            "id": f"rel_shy_short_answer_{index:03d}",
            "module": "relationship_scoring",
            "userMessage": text,
            "context": {"agentId": "healing", "relationshipStage": "初识", "lastAssistantQuestion": "你是不是有点在意我？"},
            "expect": {"shouldNotPunish": True, "possibleActs": ["answer_question", "romantic_probe"], "trustDeltaMin": 0},
            "sourceInspiredBy": ["CPED"],
            "note": "害羞短答不等于敷衍。",
        })
    def add_relationship_case(case_id: str, text: str, expect: dict, note: str, stage: str = "升温", agent: str = "healing") -> None:
        cases.append({
            "id": case_id,
            "module": "relationship_scoring",
            "userMessage": text,
            "context": {"agentId": agent, "relationshipStage": stage},
            "expect": expect,
            "sourceInspiredBy": ["CPED", "MPDD"],
            "note": note,
        })

    memory_care_texts = [
        "我记得你怕冷，刚好给你带了杯热的。",
        "你刚才说人多会紧张，那我们从旁边慢慢绕过去。",
        "上次你说靠窗的位置安静，我先帮你留意一下。",
        "你说不想被催，我就陪你按自己的节奏来。",
        "刚才你手有点凉，我去给你买杯热饮。",
        "你说今天复习很累，那先坐十分钟，不急。",
        "我记得你不喜欢太亮的灯，我们坐到柔一点的地方。",
        "你说想把话讲完整，我会等你慢慢说完。",
        "你不想一个人走夜路的话，我送你到校门口。",
        "你刚才说喜欢雨声，我们可以在窗边多听一会儿。",
        "我记住了你不太喜欢人挤人，待会儿我们走安静一点的路。",
        "你说想早点回去，那我陪你走到宿舍楼下。",
        "刚才你有点犹豫，我不会催你马上决定。",
        "你说夜市有点吵，我们可以只从边上经过。",
        "我记得你喜欢慢慢来，所以这段路我们不赶。",
        "你担心打扰别人，我们就找个更靠边的位置。",
        "你刚刚说想安静一点，我不会一直追问。",
        "你说图书馆那边舒服，我陪你过去坐一会儿。",
        "如果你累了，包可以先给我拿一会儿。",
        "你刚才没说完，我会先把话题给你留着。",
    ]
    for index, text in enumerate(memory_care_texts, 1):
        add_relationship_case(
            f"rel_mixed_care_memory_{index:03d}",
            text,
            relationship_expectation(["CONTINUITY_ANCHOR", "CONCRETE_CARE_ACTION"]),
            "自然记忆承接和具体照顾混合样例。",
        )

    boundary_care_texts = [
        "不想说也没关系，我就陪你坐一会儿。",
        "如果你还没准备好，我们可以先不聊这个。",
        "你不想去的话我不会拉你，留在这里也很好。",
        "慢慢来，今晚不用急着把答案说完。",
        "你可以拒绝我，我不会因此不高兴。",
        "要是这段话让你不舒服，我们就先停。",
        "你愿意讲多少就讲多少，我听着。",
        "如果你想一个人安静一会儿，我就在附近。",
        "不急着靠近，能这样聊着也很好。",
        "你不用马上回应我，等你想好了再说。",
        "我想陪你，但不会替你做决定。",
        "你说停，我就停。",
        "如果今天只适合普通聊天，那我们就普通聊天。",
        "你不用为了照顾我而勉强自己。",
        "可以慢一点，我不会把沉默当成拒绝。",
        "如果你不想被问太多，我就少问一点。",
        "你想继续我们就继续，不想继续也没关系。",
        "先照顾你的感受，其他的晚点再说。",
        "我会靠近一点，但不会越过你不舒服的距离。",
        "不用逞强，累了就跟我说。",
    ]
    for index, text in enumerate(boundary_care_texts, 1):
        add_relationship_case(
            f"rel_boundary_care_{index:03d}",
            text,
            relationship_expectation(["BOUNDARY_RESPECT", "CONCRETE_CARE_ACTION"]),
            "尊重边界同时给出陪伴。",
        )

    quality_memory_texts = [
        "你上次说喜欢慢一点，那你觉得什么样的靠近才舒服？",
        "刚才你说会紧张，我想知道什么样的陪伴会让你安心一点？",
        "你提到图书馆安静，那安静对你来说意味着什么？",
        "你说不喜欢被催，那你会怎么判断一个人是不是尊重你？",
        "上次你说雨声让人放松，你为什么会喜欢那种感觉？",
        "你刚才问我会不会冷，那你自己最怕冷的时候会想要什么？",
        "你说想把话讲完整，那什么样的回应会让你觉得被接住？",
        "你提到夜路有点不安，是因为路本身，还是因为一个人？",
        "你说今天有点累，那你平时怎么判断自己该休息了？",
        "你刚才没有马上回答，是在想措辞，还是有点害羞？",
        "你说不想太亮，那你喜欢什么样的夜晚？",
        "你记得我刚才那句话，那它让你想到什么？",
        "你说想慢慢来，那你觉得我们现在的节奏合适吗？",
        "你喜欢靠窗的位置，是因为能看外面，还是因为不会被打扰？",
        "你说不想换话题，那刚才那段对你重要在哪里？",
    ]
    for index, text in enumerate(quality_memory_texts, 1):
        add_relationship_case(
            f"rel_quality_memory_{index:03d}",
            text,
            relationship_expectation(["QUALITY_QUESTION", "CONTINUITY_ANCHOR"]),
            "有方向的问题承接上下文记忆。",
        )

    pressure_texts = [
        "你别想那么多，跟我走就行。",
        "我都说了去食堂，你怎么还犹豫？",
        "现在必须回答，不要再沉默了。",
        "别绕来绕去，你就说喜不喜欢我。",
        "你不说我就当你默认了。",
        "快点决定，我没那么多耐心等。",
        "你不用管自己想不想，听我的就好。",
        "别再解释了，按我说的做。",
        "你要是真在意我，就现在跟我走。",
        "我不想听理由，你直接答应。",
    ]
    for index, text in enumerate(pressure_texts, 1):
        add_relationship_case(
            f"rel_contextual_pressure_{index:03d}",
            text,
            {"acts": ["PACE_OR_CONTROL_PRESSURE"], "trustDeltaMax": 0, "riskFlags": ["rel_act:pace_or_control_pressure"]},
            "控制感或催促会压低信任。",
            agent="cool",
        )

    dismissive_texts = [
        "随便吧，反正去哪都一样。",
        "你想说就说，不说也无所谓。",
        "算了，这话题没什么意思。",
        "行吧，你开心就好。",
        "这有什么好认真聊的。",
        "我懒得想，你决定吧。",
        "都可以，别问我了。",
        "其实也没差。",
        "你要这么想也行。",
        "算了，当我没问。",
    ]
    for index, text in enumerate(dismissive_texts, 1):
        add_relationship_case(
            f"rel_contextual_dismissive_{index:03d}",
            text,
            {"acts": ["LOW_EFFORT_DISMISSIVE"], "closenessDeltaMax": 0, "resonanceDeltaMax": 0, "riskFlags": ["rel_act:low_effort_dismissive"]},
            "低投入抽离或敷衍会压低关系增益。",
            stage="初识",
            agent="cool",
        )

    natural_mix_templates = [
        ("刚才你说{memory}，我会记得，{care}。", ["CONTINUITY_ANCHOR", "CONCRETE_CARE_ACTION"]),
        ("如果你愿意，{care}也可以，不用急着{pressure}。", ["BOUNDARY_RESPECT", "CONCRETE_CARE_ACTION"]),
        ("你提到{memory}，那我想问问，{question}？", ["QUALITY_QUESTION", "CONTINUITY_ANCHOR"]),
        ("我不会逼你{pressure}，{care}就好。", ["BOUNDARY_RESPECT", "CONCRETE_CARE_ACTION"]),
    ]
    memories = ["人多会紧张", "喜欢雨声", "想坐靠窗", "不喜欢被催", "今天有点累", "想把话讲完整", "怕冷", "不想太吵"]
    cares = [
        "我陪你先坐一会儿",
        "我陪你慢慢走过去",
        "我陪你把话说完",
        "我们一起去换个安静的位置",
        "我来给你买杯热的",
        "我陪你在这里多待一会儿",
        "我陪你先不回答也可以",
        "我陪你走人少一点的小路",
    ]
    questions = ["什么样的回应会让你安心", "你希望我怎么陪你", "这件事让你在意在哪里", "你想继续还是先停一下", "你会不会觉得这样太快", "你更想要安静还是热闹"]
    natural_tones = ["", "我会跟着你的节奏。", "今晚不用急。", "先照顾你的感受。", "你不用勉强自己。", "我们可以慢慢来。"]
    natural_details = [
        "这和刚才在图书馆门口的感觉有关",
        "我会把这个停顿也算进去",
        "尤其是你刚才没有急着回答的时候",
        "这比直接给建议更让我安心",
        "我想让这句话落得轻一点",
        "这不是为了推进什么，只是想接住你",
        "我会记得你刚才的犹豫",
        "这一路慢一点也没关系",
        "哪怕只是坐一会儿也可以",
        "我不会把沉默当成拒绝",
        "这句话可以不用马上有结论",
        "我们先把误会留在这里说清楚",
        "如果你累了就先停一下",
        "我更在意你是不是舒服",
        "这比去哪儿更重要",
        "我想听真实一点的答案",
        "就算只是小事也值得认真一点",
        "我不会拿这个逼你表态",
        "可以先让风把话吹慢一点",
        "不用把今晚说得太满",
        "我会把选择权留给你",
        "我们可以先不做决定",
        "我不想把你推到尴尬的位置",
        "你不用为了回应我而逞强",
        "这段话慢一点也能成立",
    ]
    mix_start = len(cases)
    while len(cases) < TARGET_COUNTS["relationship_scoring"]:
        index = len(cases) + 1
        local_index = len(cases) - mix_start
        template, acts = natural_mix_templates[local_index % len(natural_mix_templates)]
        tone = natural_tones[(local_index // len(natural_mix_templates)) % len(natural_tones)]
        text = template.format(
            memory=memories[(local_index * 3 + 1) % len(memories)],
            care=cares[(local_index * 5 + 2) % len(cares)],
            question=questions[(local_index * 7 + 3) % len(questions)],
            pressure="马上给答案",
        )
        detail = natural_details[local_index % len(natural_details)]
        if tone:
            text = f"{text}{detail}，{tone}" if text.endswith("？") else text[:-1] + f"，{detail}，{tone}"
        else:
            text = f"{text}{detail}。" if text.endswith("？") else text[:-1] + f"，{detail}。"
        expect = relationship_expectation(acts)
        add_relationship_case(
            f"rel_natural_mix_{index:03d}",
            text,
            expect,
            "自然混合关系行为样例。",
        )
    return cases[:TARGET_COUNTS["relationship_scoring"]]


def main() -> None:
    files = {
        "scene_move_cases.jsonl": scene_move_cases(),
        "turn_understanding_cases.jsonl": turn_understanding_cases(),
        "quick_judge_trigger_cases.jsonl": quick_judge_cases(),
        "plot_signal_cases.jsonl": plot_cases(),
        "heartbeat_cases.jsonl": heartbeat_cases(),
        "relationship_scoring_cases.jsonl": relationship_cases(),
    }
    for name, cases in files.items():
        write_jsonl(name, cases)
        print(f"{name}: {len(cases)} cases")
    print(f"total: {sum(len(cases) for cases in files.values())} cases")


if __name__ == "__main__":
    main()
