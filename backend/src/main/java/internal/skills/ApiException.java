package internal.skills;

class ApiException extends RuntimeException {
    final int status;
    final String code;
    ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Skill 不存在或未发布"); }
    static ApiException conflict() { return new ApiException(409, "VERSION_CONFLICT", "记录已变更，请刷新后重新编辑；本次输入未提交"); }
    static ApiException bad(String message) { return new ApiException(400, "INVALID_ARGUMENT", message); }
}
