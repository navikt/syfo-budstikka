-- Grant SELECT privileges on all tables in the PUBLIC schema to the esyfo-analyse role
GRANT SELECT ON ALL TABLES IN SCHEMA PUBLIC TO "esyfo-analyse";
ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT ON TABLES TO "esyfo-analyse";
