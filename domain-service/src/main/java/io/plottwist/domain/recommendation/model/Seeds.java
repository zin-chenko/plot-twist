package io.plottwist.domain.recommendation.model;

import java.util.*;

public record Seeds(List<UUID> positive, List<UUID> negative) {}
