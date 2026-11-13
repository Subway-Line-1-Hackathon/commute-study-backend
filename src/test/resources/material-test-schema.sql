CREATE DOMAIN material_file_type AS VARCHAR(10);
CREATE DOMAIN material_kind AS VARCHAR(20);
CREATE DOMAIN material_status AS VARCHAR(20);
CREATE DOMAIN question_type AS VARCHAR(10);
CREATE DOMAIN question_origin AS VARCHAR(10);
CREATE TABLE app_user(id UUID PRIMARY KEY, nickname VARCHAR(50), created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE subject(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id UUID REFERENCES app_user(id), name VARCHAR(100), exam_date DATE, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, deleted_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE material(
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, subject_id BIGINT REFERENCES subject(id),
 file_url TEXT NOT NULL, file_name TEXT, file_type material_file_type, kind material_kind, size BIGINT,
 status material_status, page_count INT, error_message TEXT, expires_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, deleted_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE material_delete_object (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, material_id BIGINT NOT NULL,
 object_ref TEXT NOT NULL, retry_order_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(material_id,object_ref));
CREATE TABLE material_page(material_id BIGINT REFERENCES material(id), page INT, text TEXT, PRIMARY KEY(material_id,page));
CREATE TABLE category(
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, subject_id BIGINT REFERENCES subject(id),
 material_id BIGINT REFERENCES material(id), name VARCHAR(100), page_start INT, page_end INT,
 summary TEXT, "order" INT, question_status VARCHAR(20));
CREATE TABLE question(
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, category_id BIGINT REFERENCES category(id),
 type question_type, difficulty INT, stem TEXT, choices JSONB, answer TEXT, accepted_answers JSONB,
 rubric TEXT, explanation TEXT, source_page INT, concept_tags JSONB, origin question_origin);
