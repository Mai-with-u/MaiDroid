// 测试用 D1 适配器：用 Node 内置 node:sqlite 跑真实 SQL，并加载正式 schema.sql，免去为每条新查询手写假实现。
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

export function createSqliteD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));

  function makeStmt(sql) {
    let args = [];
    const stmt = {
      sql,
      bind(...values) {
        args = values.map((v) => (v === undefined ? null : v));
        return stmt;
      },
      async first(col) {
        const row = db.prepare(sql).get(...args);
        if (!row) return null;
        const plain = { ...row };
        return col ? plain[col] : plain;
      },
      async all() {
        return { results: db.prepare(sql).all(...args).map((r) => ({ ...r })), success: true, meta: {} };
      },
      async run() {
        const info = db.prepare(sql).run(...args);
        return { success: true, meta: { changes: Number(info.changes), last_row_id: Number(info.lastInsertRowid) } };
      },
    };
    return stmt;
  }

  return {
    raw: db,
    prepare: makeStmt,
    // D1 的 batch 是事务性的：任一条失败整体回滚
    async batch(stmts) {
      db.exec("BEGIN");
      try {
        const out = [];
        for (const s of stmts) out.push(await s.run());
        db.exec("COMMIT");
        return out;
      } catch (e) {
        db.exec("ROLLBACK");
        throw e;
      }
    },
  };
}
