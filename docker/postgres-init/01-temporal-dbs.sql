-- Базы для Temporal (создаются при первой инициализации тома).
CREATE DATABASE "temporal";
CREATE DATABASE "temporal_visibility";
GRANT ALL PRIVILEGES ON DATABASE "temporal" TO card;
GRANT ALL PRIVILEGES ON DATABASE "temporal_visibility" TO card;
