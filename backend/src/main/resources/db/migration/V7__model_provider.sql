UPDATE app_settings SET body = '{"modelProvider":"openai"}'::jsonb || body WHERE scope = 'global';
