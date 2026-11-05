-- Remove the "local channel" (RemoteProtocol.LOCAL): delete local directory-sync connections created by older versions along with their mount records.
-- Mounts are deleted before connections, without relying on foreign-key cascades.
DELETE FROM remote_mounts WHERE connectionId IN (SELECT id FROM remote_connections WHERE protocol = 'LOCAL');
DELETE FROM remote_connections WHERE protocol = 'LOCAL';
