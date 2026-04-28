# Sources For Local Rule Coverage

这里记录测试覆盖维度的公开来源。测试样例不会复制这些数据集里的对话原文，只借鉴它们的任务定义、标签体系和评估视角。

## CPED

- Link: https://github.com/scutcyr/CPED
- Paper: https://huggingface.co/papers/2205.14727
- 借鉴点：中文个性化与情绪化对话、性别、人物属性、13 类情绪、19 类 dialogue act、场景标签。
- 对应本项目：角色性格区分、情绪承接、性别代词、`TurnUnderstandingService` 的用户回应动作。
- 生成器使用方式：只借鉴 `question / answer / agreement / disagreement / comfort / reject` 等动作形态，不复制 CPED 原文。

## NaturalConv

- Link: https://aaai.org/papers/14006-naturalconv-a-chinese-dialogue-dataset-towards-multi-turn-topic-driven-conversation/
- Dataset card: https://huggingface.co/datasets/xywang1/NaturalConv
- 借鉴点：多轮话题驱动对话、自然话题转移、话题深入与平滑切换；数据集约 19.9K 对话、400K 话语。
- 对应本项目：`topic_only`、地点词不等于移动、用户问偏好时不应强行转场。
- 生成器使用方式：构造地点/天气/氛围作为话题的样例，避免关键词误判成场景移动。

## CrossWOZ

- Link: https://github.com/thu-coai/CrossWOZ
- Paper summary: https://huggingface.co/papers/2002.11893
- 借鉴点：任务型对话状态、dialogue act、用户目标、跨领域自然转移。
- 对应本项目：计划接受/拒绝/改约、`DialogueContinuityState`、`SceneMoveIntentService`、目标地点状态。
- 生成器使用方式：把酒店/餐厅/景点/交通等任务目标结构改写成校园里的食堂、操场、图书馆、宿舍、路上。

## MPDD

- Link: https://scholars.lib.ntu.edu.tw/handle/123456789/581351
- 借鉴点：情绪流和人际关系标签。
- 对应本项目：关系评分、张力状态、边界尊重、敷衍抽离、心跳回复的关系承接。
- 生成器使用方式：构造边界尊重、控制压力、敷衍抽离、修复关系、心跳承接等样例。

## Internal Mapping

- `scene_move_cases.jsonl` 主要受 NaturalConv 和 CrossWOZ 启发。
- `turn_understanding_cases.jsonl` 主要受 CPED 和 CrossWOZ 启发。
- `quick_judge_trigger_cases.jsonl` 主要来自项目自身 QuickJudge 设计，再参考 dialogue act 的模糊/纠错场景。
- `plot_signal_cases.jsonl` 主要来自项目自身 PlotDirector/PlotPressure 规则。
- `heartbeat_cases.jsonl` 主要来自项目自身 presence 机制，再参考多轮连续性和关系承接。
- `relationship_scoring_cases.jsonl` 主要受 CPED、MPDD 和项目 `UserRelationalAct` 结构启发。
