/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.adk.events.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The shared contract's fast, real SQLite execution (Docker is not required). */
@Timeout(120)
final class DatabaseSessionServiceTest {
  @TempDir Path directory;

  /** Keeps the shared contract honest: it must describe what the reference in core really does. */
  @TestFactory
  Stream<DynamicTest> inMemoryReferenceContract() {
    InMemorySessionService reference =
        new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
    return new SessionServiceContract(() -> reference).tests();
  }

  @TestFactory
  Stream<DynamicTest> sqliteContract() throws Exception {
    var source = DatabaseSchemaMigratorTest.source(directory.resolve("contract.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    printVersion(source, "default");
    return Stream.concat(
        new DatabaseSessionContract(source, DatabaseSessionService.Dialect.SQLITE).tests(),
        new DatabaseSessionJdbcContract(source, DatabaseSessionService.Dialect.SQLITE).tests());
  }

  @TestFactory
  Stream<DynamicTest> sqliteWalContract() throws Exception {
    var source = DatabaseSchemaMigratorTest.source(directory.resolve("wal-contract.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
    }
    printVersion(source, "wal");
    return Stream.concat(
        new DatabaseSessionContract(source, DatabaseSessionService.Dialect.SQLITE).tests(),
        new DatabaseSessionJdbcContract(source, DatabaseSessionService.Dialect.SQLITE).tests());
  }

  @TestFactory
  Stream<DynamicTest> sqliteProcessCrashRecovery() {
    return Stream.of("delete", "wal")
        .flatMap(
            journal ->
                Stream.of("before", "after")
                    .map(
                        boundary ->
                            dynamicTest(
                                journal + " process exits " + boundary + " commit",
                                () -> {
                                  Path database =
                                      directory.resolve(journal + "-" + boundary + ".db");
                                  runCrashProbe(database, journal, boundary, "write", 41);
                                  runCrashProbe(database, journal, boundary, "recover", 0);
                                })));
  }

  private void runCrashProbe(
      Path database, String journal, String boundary, String mode, int expectedExit)
      throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path log = directory.resolve(journal + "-" + boundary + "-" + mode + ".log");
    Process process =
        new ProcessBuilder(
                executable,
                "-cp",
                classpath,
                ProcessCrashProbe.class.getName(),
                database.toString(),
                journal,
                boundary,
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertWithMessage("Child process timed out; log: %s", log)
          .that(process.waitFor(45, TimeUnit.SECONDS))
          .isTrue();
      assertWithMessage("Child process log (%s):\n%s", log, Files.readString(log))
          .that(process.exitValue())
          .isEqualTo(expectedExit);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  /** Launched in separate JVMs so recovery cannot use live objects or normal shutdown cleanup. */
  public static final class ProcessCrashProbe {
    public static void main(String[] args) throws Exception {
      var source = DatabaseSchemaMigratorTest.source(Path.of(args[0]));
      DatabaseSessionService service = new DatabaseSessionService(source);
      SessionKey key = new SessionKey("crash-app", "user", "session");
      Event seed = DatabaseSessionContract.event("seed", 1000, Map.of());
      Event change = DatabaseSessionContract.event("change", 2000, scopedState("after"));
      boolean afterCommit = args[2].equals("after");
      if (args[3].equals("write")) {
        try (var connection = source.getConnection();
            var statement = connection.createStatement();
            var result = statement.executeQuery("PRAGMA journal_mode=" + args[1])) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo(args[1]);
        }
        service.migrate().blockingAwait();
        Session session =
            service
                .createSession(key.appName(), key.userId(), scopedState("before"), key.id())
                .blockingGet();
        service.appendEvent(session, seed).blockingGet();
        JdbcProbe probe = new JdbcProbe(source, DatabaseSessionService.Dialect.SQLITE);
        probe.hook =
            (sql, after) -> {
              if (sql.equals("COMMIT") && after == afterCommit) {
                // Halt inside appendEvent: no finally blocks, connection close, or publication.
                Runtime.getRuntime().halt(41);
              }
            };
        new DatabaseSessionService(probe).appendEvent(session, change).blockingGet();
        throw new AssertionError("Append returned without reaching the crash boundary");
      }
      assertThat(args[3]).isEqualTo("recover");
      Session recovered = service.getSession(key, null).blockingGet();
      assertThat(recovered).isNotNull();
      List<Event> expected = afterCommit ? List.of(seed, change) : List.of(seed);
      assertThat(recovered.immutableEvents()).containsExactlyElementsIn(expected).inOrder();
      assertThat(recovered.state())
          .containsExactlyEntriesIn(scopedState(afterCommit ? "after" : "before"));
      assertThat(recovered.lastUpdateTime())
          .isEqualTo(Instant.ofEpochMilli(afterCommit ? 2000 : 1000));
      Event continued =
          DatabaseSessionContract.event("continued", 3000, Map.of("value", "continued"));
      service.appendEvent(recovered, continued).blockingGet();
      Session reloaded = service.getSession(key, null).blockingGet();
      assertThat(reloaded.immutableEvents().stream().map(Event::id).toList())
          .containsExactlyElementsIn(
              Stream.concat(expected.stream(), Stream.of(continued)).map(Event::id).toList())
          .inOrder();
      assertThat(reloaded.state()).containsEntry("value", "continued");
    }

    private static Map<String, Object> scopedState(String value) {
      return Map.of("value", value, "app:value", value, "user:value", value);
    }
  }

  private static void printVersion(javax.sql.DataSource source, String journal) throws Exception {
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      System.out.printf(
          "ADK_DATABASE_TEST backend=sqlite journal=%s engine=%s driver=%s java=%s%n",
          journal,
          metadata.getDatabaseProductVersion(),
          metadata.getDriverVersion(),
          System.getProperty("java.version"));
    }
  }
}
