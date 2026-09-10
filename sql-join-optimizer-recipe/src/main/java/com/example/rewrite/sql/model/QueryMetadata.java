package com.example.rewrite.sql.model;

import java.util.List;

public record QueryMetadata(
        String rawQuery,
        String returnTypeFqn,
        String declaringClassFqn,
        String methodName,
        String sourceModule,
        String sourcePath,
        int lineNumber
) {
    public String getFullQueryKey() {
        return declaringClassFqn + "#" + methodName;
    }
}
