package com.example.rewrite.sql.model;

import java.util.Objects;

public class QueryMetadata {
    private final String rawQuery;
    private final String returnTypeFqn;
    private final String declaringClassFqn;
    private final String methodName;
    private final String sourceModule;
    private final String sourcePath;
    private final int lineNumber;

    public QueryMetadata(String rawQuery, String returnTypeFqn, String declaringClassFqn,
                         String methodName, String sourceModule, String sourcePath, int lineNumber) {
        this.rawQuery = rawQuery;
        this.returnTypeFqn = returnTypeFqn;
        this.declaringClassFqn = declaringClassFqn;
        this.methodName = methodName;
        this.sourceModule = sourceModule;
        this.sourcePath = sourcePath;
        this.lineNumber = lineNumber;
    }

    public String getFullQueryKey() {
        return declaringClassFqn + "#" + methodName;
    }

    public String rawQuery() { return rawQuery; }
    public String returnTypeFqn() { return returnTypeFqn; }
    public String declaringClassFqn() { return declaringClassFqn; }
    public String methodName() { return methodName; }
    public String sourceModule() { return sourceModule; }
    public String sourcePath() { return sourcePath; }
    public int lineNumber() { return lineNumber; }

    public String getRawQuery() { return rawQuery; }
    public String getReturnTypeFqn() { return returnTypeFqn; }
    public String getDeclaringClassFqn() { return declaringClassFqn; }
    public String getMethodName() { return methodName; }
    public String getSourceModule() { return sourceModule; }
    public String getSourcePath() { return sourcePath; }
    public int getLineNumber() { return lineNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryMetadata that = (QueryMetadata) o;
        return lineNumber == that.lineNumber &&
                Objects.equals(rawQuery, that.rawQuery) &&
                Objects.equals(returnTypeFqn, that.returnTypeFqn) &&
                Objects.equals(declaringClassFqn, that.declaringClassFqn) &&
                Objects.equals(methodName, that.methodName) &&
                Objects.equals(sourceModule, that.sourceModule) &&
                Objects.equals(sourcePath, that.sourcePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawQuery, returnTypeFqn, declaringClassFqn, methodName, sourceModule, sourcePath, lineNumber);
    }
}
