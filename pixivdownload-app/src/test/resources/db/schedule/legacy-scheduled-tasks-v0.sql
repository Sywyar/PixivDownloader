CREATE TABLE scheduled_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    type TEXT NOT NULL,
    params_json TEXT NOT NULL,
    trigger_kind TEXT NOT NULL,
    interval_minutes INTEGER,
    cron_expr TEXT,
    cookie_mode TEXT NOT NULL,
    cookie_snapshot TEXT,
    proxy_snapshot TEXT,
    next_run_time INTEGER,
    last_run_time INTEGER,
    last_status TEXT,
    last_message TEXT,
    watermark_id INTEGER,
    run_started_time INTEGER,
    account_id TEXT,
    ack_warning_time INTEGER,
    pending_retry_armed INTEGER DEFAULT 0,
    created_time INTEGER NOT NULL
);

CREATE INDEX idx_scheduled_tasks_next_run ON scheduled_tasks(next_run_time);
CREATE INDEX idx_scheduled_tasks_account ON scheduled_tasks(account_id);

CREATE TABLE scheduled_task_pending (
    task_id INTEGER NOT NULL,
    work_id INTEGER NOT NULL,
    reason TEXT,
    attempts INTEGER DEFAULT 0,
    first_seen_time INTEGER,
    last_attempt_time INTEGER,
    PRIMARY KEY(task_id, work_id)
);

INSERT INTO scheduled_tasks(id, name, enabled, type, params_json, trigger_kind,
    interval_minutes, cron_expr, cookie_mode, cookie_snapshot, proxy_snapshot,
    next_run_time, last_run_time, last_status, last_message, watermark_id,
    run_started_time, account_id, ack_warning_time, pending_retry_armed, created_time)
VALUES
    (1, 'user-new legacy', 1, 'USER_NEW', '{"kind":"illust","source":{"userId":"1001"}}',
     'interval', 30, NULL, 'bound', 'PHPSESSID=1001_legacy; device=fixture', '127.0.0.1:7890',
     1700000100000, 1700000000000, 'OK', NULL, 9007199254740991,
     1700000000500, '1001', NULL, 1, 1699999000000),
    (2, 'user-request disabled interrupted', 0, 'USER_REQUEST', '{"kind":"illust","source":{"userId":"2002"}}',
     'cron', NULL, '0 0 * * * *', 'restricted', NULL, NULL,
     1700000200000, 1700000001000, 'ERROR', 'legacy interrupted run', NULL,
     1700000002500, NULL, NULL, 0, 1699999001000),
    (3, 'search credential suspended', 1, 'SEARCH', '{"kind":"illust","source":{"keyword":"fixture"}}',
     'interval', 15, NULL, 'bound', 'PHPSESSID=3003_expired', NULL,
     1700000300000, 1700000002000, 'AUTH_EXPIRED', 'credential expired', 303,
     NULL, '3003', NULL, 0, 1699999002000),
    (4, 'series policy suspended', 1, 'SERIES', '{"kind":"illust","source":{"seriesId":"4004"}}',
     'interval', 60, NULL, 'bound', 'PHPSESSID=4004_policy', NULL,
     1700000400000, 1700000003000, 'OVERUSE_PAUSED', 'policy warning', 404,
     NULL, '4004', 1700000004000, 0, 1699999003000),
    (5, 'bookmarks manually suspended', 1, 'MY_BOOKMARKS', '{"kind":"novel","source":{"rest":"show"}}',
     'interval', 45, NULL, 'bound', 'PHPSESSID=5005_manual', NULL,
     1700000500000, 1700000004000, 'PAUSED', 'manual pause', NULL,
     NULL, '5005', NULL, 1, 1699999004000),
    (6, 'follow source suspended', 1, 'FOLLOW_LATEST', '{"kind":"illust","source":{}}',
     'interval', 20, NULL, 'bound', 'PHPSESSID=6006_source', NULL,
     1700000600000, 1700000005000, 'SOURCE_UNAVAILABLE', 'source missing', 606,
     NULL, '6006', NULL, 0, 1699999005000),
    (7, 'collection ambiguous pending', 1, 'COLLECTION', '{"kind":"mixed","source":{"collectionId":"7007"}}',
     'interval', 90, NULL, 'bound', 'PHPSESSID=7007_collection', NULL,
     1700000700000, NULL, NULL, NULL, NULL,
     NULL, '7007', NULL, 1, 1699999006000);

INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts, first_seen_time, last_attempt_time)
VALUES
    (1, 9007199254740991, 'network', 2, 1700000000100, 1700000000200),
    (5, 42, 'download', 1, 1700000004100, 1700000004200),
    (7, 77, 'mixed-kind-unknown', 3, 1700000006100, 1700000006200);
