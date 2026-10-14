package com.lux.backend.task;

import com.lux.backend.chat.ConversationContent.TaskQuerySnapshot;
import com.lux.backend.chat.ConversationContent.TaskSubmissionReceipt;
import com.lux.backend.common.Ids;
import com.lux.backend.user.UserService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** Session Task 工具接口与前端卡片查询；受理回执不表示 provider 已接受或生成完成。 */
@RestController
public class TaskController {
    /** 从路径确认所有者存在，旧 Header 不参与资源归属。 */
    private final UserService users;
    /** 工具受理回执、一致查询快照及前端卡片共用业务服务。 */
    private final TaskService tasks;
    public TaskController(TaskService tasks, UserService users) { this.tasks = tasks; this.users = users; }

    /** 立即受理 Session Task，返回固定 task_id/accepted 而非可变状态。 */
    @PostMapping("/internal/v2/users/{user_id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskSubmissionReceipt create(@PathVariable("user_id") String userId,
                                         @RequestBody TaskService.CreateSessionTaskRequest request) {
        return tasks.createSessionTask(users.get(Ids.require(userId)).id(), request);
    }

    /** 使用原 operation 查询未知受理；404 本身不能证明原付费操作从未发生。 */
    @GetMapping("/internal/v2/users/{user_id}/tasks/by-operation/{operation_id}")
    public ResponseEntity<TaskSubmissionReceipt> byOperation(@PathVariable("user_id") String userId,
                                                              @PathVariable("operation_id") String operationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.receiptByOperation(users.get(Ids.require(userId)).id(), operationId));
    }

    /** 模型读取当前 Session 的一致快照，不能通过其他 Session 的 Task ID 混入结果。 */
    @GetMapping("/internal/v2/users/{user_id}/sessions/{session_id}/tasks/{id}")
    public ResponseEntity<TaskQuerySnapshot> snapshot(@PathVariable("user_id") String userId,
            @PathVariable("session_id") String sessionId, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.snapshot(users.get(Ids.require(userId)).id(), sessionId, id));
    }

    @GetMapping("/api/v1/users/{user_id}/tasks/{id}")
    public ResponseEntity<TaskView> get(@PathVariable("user_id") String userId, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(tasks.view(users.get(Ids.require(userId)).id(), id));
    }

    @PostMapping("/api/v1/users/{user_id}/tasks/batch")
    public List<TaskView> batch(@PathVariable("user_id") String userId, @RequestBody BatchRequest request) {
        return tasks.batchViews(users.get(Ids.require(userId)).id(), request.ids());
    }
    public record BatchRequest(List<String> ids) {}
}
