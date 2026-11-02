package io.plottwist.api.attachment.model;

import java.util.*;

public record StreamMetadata(int status, Map<String, List<String>> headers) {}
