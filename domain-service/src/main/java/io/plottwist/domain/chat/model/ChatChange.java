package io.plottwist.domain.chat.model;

import java.util.*;

public record ChatChange(UUID eventId, UUID chatId, List<UUID> recipients) {}
