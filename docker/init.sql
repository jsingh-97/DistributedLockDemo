CREATE TABLE IF NOT EXISTS item_availability
(
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL
    );

INSERT INTO item_availability (id, name, quantity)
VALUES (1, 'Limited Edition Sneaker', 1);