function includesAnyKeyword(text, keywordsAny) {
  if (!keywordsAny || keywordsAny.length === 0) {
    return true;
  }

  return keywordsAny.some((keyword) => text.includes(keyword));
}

export class EventEngine {
  findTriggeredEvent(agent, session, userMessage) {
    const normalized = userMessage.trim().toLowerCase();
    const triggeredIds = new Set(session.storyEventProgress.triggeredEventIds);
    const affectionScore = session.relationshipState.affectionScore;
    const currentUserTurns = session.userTurnCount + 1;

    return (
      agent.storyEvents.find((event) => {
        if (triggeredIds.has(event.id)) {
          return false;
        }

        if (currentUserTurns < event.unlockAtMessages) {
          return false;
        }

        if (affectionScore < event.minAffection) {
          return false;
        }

        return includesAnyKeyword(normalized, event.keywordsAny.map((keyword) => keyword.toLowerCase()));
      }) || null
    );
  }
}
