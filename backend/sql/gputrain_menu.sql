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

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id
FROM sys_menu menu
WHERE (
        (menu.menu_name = 'GPUTrain' AND menu.parent_id = 0)
        OR menu.perms = 'train:overview:view'
      )
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu role_menu
      WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id
  );
