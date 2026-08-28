INSERT INTO permissions(user_type, module, can_view, can_create, can_edit, can_delete, can_approve, can_export) VALUES
('super_admin', 'data_import', 1, 1, 1, 1, 1, 1),
('super_admin', 'data_warehouse', 1, 1, 1, 1, 1, 1),
('platform_admin', 'data_import', 1, 1, 1, 0, 1, 1),
('platform_admin', 'data_warehouse', 1, 1, 1, 0, 1, 1),
('admin', 'data_import', 1, 1, 1, 0, 0, 1),
('admin', 'data_warehouse', 1, 1, 1, 0, 0, 1),
('institution_admin', 'data_import', 1, 1, 0, 0, 0, 1),
('institution_admin', 'data_warehouse', 1, 1, 0, 0, 0, 1),
('research_analyst', 'data_import', 1, 1, 0, 0, 0, 1),
('research_analyst', 'data_warehouse', 1, 1, 0, 0, 0, 1)
ON CONFLICT (user_type, module) DO UPDATE SET
    can_view = EXCLUDED.can_view,
    can_create = EXCLUDED.can_create,
    can_edit = EXCLUDED.can_edit,
    can_delete = EXCLUDED.can_delete,
    can_approve = EXCLUDED.can_approve,
    can_export = EXCLUDED.can_export;
