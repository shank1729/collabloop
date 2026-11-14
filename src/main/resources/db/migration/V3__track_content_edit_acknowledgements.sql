ALTER TABLE document_version ADD COLUMN client_edit_id UUID;
CREATE UNIQUE INDEX uk_document_version_client_edit
    ON document_version (document_id, client_edit_id);
