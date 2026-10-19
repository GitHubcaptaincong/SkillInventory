package internal.skills.exception;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Skill 不存在或未发布"); }
    public static ApiException conflict() { return new ApiException(409, "VERSION_CONFLICT", "记录已变更，请刷新后重新编辑；本次输入未提交"); }
    public static ApiException bad(String message) { return new ApiException(400, "INVALID_ARGUMENT", message); }
}
