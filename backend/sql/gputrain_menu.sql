SET NAMES utf8mb4;

-- GPUTrain菜单与管理员授权，可重复执行。

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT
    'GPUTrain', 0, 1, 'gputrain', NULL, '', '', 1, 0,
    'M', '0', '0', '', 'monitor', 'admin', NOW(), 'GPUTrain训练管理目录'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_name = 'GPUTrain' AND parent_id = 0
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT
    '训练工作台', parent.menu_id, 1, 'overview', 'train/overview/index', '', '', 1, 0,
    'C', '0', '0', 'train:overview:view', 'dashboard', 'admin', NOW(), '训练平台状态总览'
FROM sys_menu parent
WHERE parent.menu_name = 'GPUTrain'
  AND parent.parent_id = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu child
      WHERE child.menu_name = '训练工作台' AND child.parent_id = parent.menu_id
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT
    m.menu_name, parent.menu_id, m.order_num, m.path, m.component, '', '', 1, 0,
    'C', '0', '0', m.perms, 'server', 'admin', NOW(), m.remark
FROM sys_menu parent
CROSS JOIN (
    SELECT '训练任务' AS menu_name, 3 AS order_num, 'jobs' AS path,
           'train/jobs/index' AS component, 'train:job:view' AS perms,
           '训练任务与状态机' AS remark
) m
WHERE parent.menu_name = 'GPUTrain'
  AND parent.parent_id = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu child
      WHERE child.perms = m.perms AND child.parent_id = parent.menu_id
        AND child.menu_type = 'C'
  );

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id
FROM sys_menu menu
WHERE (
        (menu.menu_name = 'GPUTrain' AND menu.parent_id = 0)
        OR menu.perms = 'train:overview:view'
        OR menu.perms = 'train:job:view'
      )
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu role_menu
      WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id
  );

-- SSH inventory permissions.
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '服务器连接', parent.menu_id, 4, 'servers', 'train/servers/index', 1, 0, 'C',
       '0', '0', 'train:server:view', 'server', 'admin', NOW(), '本人服务器账户与资源清单'
FROM sys_menu parent
WHERE parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:server:view');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '管理服务器', parent.menu_id, 1, '#', 1, 0, 'F',
       '0', '0', 'train:server:manage', '#', 'admin', NOW(), '服务器及账户授权管理'
FROM sys_menu parent
WHERE parent.perms = 'train:server:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:server:manage');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id FROM sys_menu menu
WHERE menu.perms IN ('train:server:view', 'train:server:manage')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = menu.menu_id);

-- Web terminal permissions (phase 10).
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '远程终端', parent.menu_id, 5, 'terminal', 'train/terminal/index', 1, 0, 'C',
       '0', '0', 'train:terminal:view', 'monitor', 'admin', NOW(), 'Web 终端通道（人工 PTY 会话）'
FROM sys_menu parent
WHERE parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:terminal:view');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '连接终端', parent.menu_id, 1, '#', 1, 0, 'F',
       '0', '0', 'train:terminal:connect', '#', 'admin', NOW(), '发起终端会话（票据签发与握手）'
FROM sys_menu parent
WHERE parent.perms = 'train:terminal:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:terminal:connect');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id FROM sys_menu menu
WHERE menu.perms IN ('train:terminal:view', 'train:terminal:connect')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = menu.menu_id);

-- Command proposal permissions (phase 11, channel B).
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '命令提案', parent.menu_id, 6, 'proposals', 'train/proposals/index', 1, 0, 'C',
       '0', '0', 'train:proposal:view', 'build', 'admin', NOW(), 'Agent 命令闭环（人工确认后白名单执行）'
FROM sys_menu parent
WHERE parent.menu_name = 'GPUTrain' AND parent.parent_id = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:proposal:view');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
SELECT '执行提案', parent.menu_id, 1, '#', 1, 0, 'F',
       '0', '0', 'train:proposal:execute', '#', 'admin', NOW(), '创建/批准/拒绝命令提案'
FROM sys_menu parent
WHERE parent.perms = 'train:proposal:view'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'train:proposal:execute');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id FROM sys_menu menu
WHERE menu.perms IN ('train:proposal:view', 'train:proposal:execute')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = menu.menu_id);
