-- Append-only is enforced at the role level, not in application code: a bug, a
-- migration, or a psql session cannot rewrite history that code merely promises
-- not to touch. Flyway itself runs as the owner and keeps full rights; the
-- application connects as a member of this role, which is where the limit bites.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'template_update_app') then
        create role template_update_app nologin;
    end if;
end
$$;

grant select, insert, update on engagement_template_state to template_update_app;
grant select, insert, update on template_version_catalog  to template_update_app;
grant select, insert, update on change_summary            to template_update_app;
grant select, insert         on processed_event           to template_update_app;

-- The decision log: read and append, nothing else.
grant select, insert on update_decision to template_update_app;

-- Never granted above, so this revokes nothing today. It is here so that a
-- later blanket grant has to argue with an explicit line rather than slip past.
revoke update, delete on update_decision from template_update_app;
revoke update, delete on update_decision from public;
