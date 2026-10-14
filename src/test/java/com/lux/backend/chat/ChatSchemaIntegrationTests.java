package com.lux.backend.chat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直接验证 MySQL CHECK/外键/唯一键，不启动 Kafka、对象服务或业务进程。 */
class ChatSchemaIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(768L * 1024 * 1024));
    private static final String OWNER = UUID.randomUUID().toString();
    private String session;
    private String run;
    private String turn;

    @BeforeAll
    static void initialize() throws SQLException {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/session-storage").load().migrate();
        execute("INSERT INTO `user`(id,display_name) VALUES (?,?)", OWNER, "Constraint test");
    }

    @AfterAll
    static void shutdown() {
        MYSQL.stop();
    }

    @BeforeEach
    void createOwnedIdentityShells() throws SQLException {
        session = UUID.randomUUID().toString();
        run = UUID.randomUUID().toString();
        turn = UUID.randomUUID().toString();
        execute("INSERT INTO `session`(id,user_id,title) VALUES (?,?,?)", session, OWNER, "Session");
        execute("INSERT INTO agent_run(id,user_id,session_id,run_index) VALUES (?,?,?,1)", run, OWNER, session);
        execute("INSERT INTO agent_turn(id,user_id,session_id,run_id,turn_index) VALUES (?,?,?,?,1)", turn, OWNER, session, run);
    }

    @Test
    void sourceShellSatisfiesReferencesWithoutInventedContent() {
        String item = UUID.randomUUID().toString();
        assertThatNoException().isThrownBy(() -> {
            shell(item, 1, 1, run, turn);
            execute("UPDATE agent_run SET source_revision=1,status='COMPLETED',finished_at=CURRENT_TIMESTAMP(6),final_item_id=? WHERE id=?", item, run);
        });
    }

    @Test
    void inputCannotAcquireRunAndTurnEvenWhenThoseParentsExist() {
        assertThatThrownBy(() -> execute("""
            INSERT INTO item(id,user_id,session_id,sequence_no,source,kind,run_id,turn_id,
                input_sequence_no,status,payload,completed_at)
            VALUES (?,?,?,1,'USER','MESSAGE',?,?,1,'COMPLETED',JSON_OBJECT('blocks',JSON_ARRAY()),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), OWNER, session, run, turn)).isInstanceOf(SQLException.class);
    }

    @Test
    void callsDistinguishCaseAndTrailingSpaceButKeepOneCallAndOneResult() throws SQLException {
        call("Call", 1);
        call("call", 2);
        call("call ", 3);
        assertThatThrownBy(() -> call("Call", 4)).isInstanceOf(SQLException.class);
        assertThatNoException().isThrownBy(() -> execute("""
            INSERT INTO item(id,user_id,session_id,sequence_no,source,kind,run_id,turn_id,item_index,
                source_revision,status,call_id,payload,completed_at)
            VALUES (?,?,?,5,'AGENT','TOOL_RESULT',?,?,5,1,'COMPLETED','Call',
                JSON_OBJECT('type','tool_error','code','TOOL_NOT_EXECUTED','message','Stopped'),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), OWNER, session, run, turn));
    }

    @Test
    void finalAnswerReferenceCannotPointIntoAnotherRun() throws SQLException {
        String otherRun = UUID.randomUUID().toString();
        String otherTurn = UUID.randomUUID().toString();
        String otherItem = UUID.randomUUID().toString();
        execute("INSERT INTO agent_run(id,user_id,session_id,run_index) VALUES (?,?,?,2)", otherRun, OWNER, session);
        execute("INSERT INTO agent_turn(id,user_id,session_id,run_id,turn_index) VALUES (?,?,?,?,1)", otherTurn, OWNER, session, otherRun);
        shell(otherItem, 1, 1, otherRun, otherTurn);
        assertThatThrownBy(() -> execute("UPDATE agent_run SET source_revision=1,status='COMPLETED',finished_at=CURRENT_TIMESTAMP(6),final_item_id=? WHERE id=?", otherItem, run))
            .isInstanceOf(SQLException.class);
    }

    @Test
    void anIdentityOnlyShellCannotClaimAnUnobservedKind() {
        assertThatThrownBy(() -> execute("""
            INSERT INTO item(id,user_id,session_id,sequence_no,source,kind,run_id,turn_id,item_index)
            VALUES (?,?,?,1,'AGENT','MESSAGE',?,?,1)
            """, UUID.randomUUID().toString(), OWNER, session, run, turn)).isInstanceOf(SQLException.class);
    }

    @Test
    void uploadsRequireAnOperationAndGeneratedAssetsCannotClaimAnUploadOperation() throws SQLException {
        String task = UUID.randomUUID().toString();
        execute("INSERT INTO task(id,user_id,session_id,operation_id,file_type,payload,status) VALUES (?,?,?,?,'IMAGE',JSON_OBJECT(),'CREATED')",
            task, OWNER, session, UUID.randomUUID().toString());
        assertThatNoException().isThrownBy(() -> asset(null, null, UUID.randomUUID().toString()));
        assertThatNoException().isThrownBy(() -> asset(task, 0, null));
        assertThatThrownBy(() -> asset(null, null, null)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> asset(task, 1, UUID.randomUUID().toString())).isInstanceOf(SQLException.class);
    }

    @Test
    void streamPositionsAreUniqueEvenBeforeAnyItemProjectionExists() throws SQLException {
        String item = UUID.randomUUID().toString();
        String sql = """
            INSERT INTO run_event(id,user_id,session_id,run_id,item_id,sequence_no,stream_seq,
                event_type,occurred_at,event_hash,payload)
            VALUES (?,?,?,?,?,?,1,'ITEM_TEXT_DELTA',CURRENT_TIMESTAMP(6),?,JSON_OBJECT())
            """;
        execute(sql, UUID.randomUUID().toString(), OWNER, session, run, item, 1, "a".repeat(64));
        assertThatThrownBy(() -> execute(sql, UUID.randomUUID().toString(), OWNER, session, run, item, 2, "b".repeat(64)))
            .isInstanceOf(SQLException.class);
    }

    private void asset(String task, Integer index, String operation) throws SQLException {
        execute("""
            INSERT INTO asset(id,user_id,task_id,source_index,operation_id,filename,media_type,size_bytes,sha256)
            VALUES (?,?,?,?,?,'result.png','image/png',1,?)
            """, UUID.randomUUID().toString(), OWNER, task, index, operation, "a".repeat(64));
    }

    private void shell(String id, int sequence, int index, String parentRun, String parentTurn) throws SQLException {
        execute("INSERT INTO item(id,user_id,session_id,sequence_no,source,run_id,turn_id,item_index) VALUES (?,?,?,?,'AGENT',?,?,?)",
            id, OWNER, session, sequence, parentRun, parentTurn, index);
    }

    private void call(String callId, int index) throws SQLException {
        execute("""
            INSERT INTO item(id,user_id,session_id,sequence_no,source,kind,run_id,turn_id,item_index,
                source_revision,status,call_id,tool_name,payload,completed_at)
            VALUES (?,?,?,?,'AGENT','TOOL_CALL',?,?,?,1,'COMPLETED',?,'get_task',
                JSON_OBJECT('arguments',JSON_OBJECT('task_id',?)),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), OWNER, session, index, run, turn, index, callId, UUID.randomUUID().toString());
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }
}
