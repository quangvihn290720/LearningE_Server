-- Kỷ nguyên dữ liệu: tăng mỗi lần kho được khôi phục từ bản sao lưu, để app biết phải đẩy lại từ đầu.
ALTER TABLE sync_seq ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1;
