-- Stable IDs are retained when Phase 3 introduces the authoritative menu source.
INSERT INTO menu_item
    (id, name, description, category, ingredients, dietary_tags, spice_level, price, currency, available)
VALUES
    ('00000000-0000-4000-8000-000000000001', 'Paneer Butter Masala',
     'Paneer in a creamy tomato and butter gravy.', 'MAIN',
     ARRAY['paneer', 'tomato', 'butter', 'cream'], ARRAY['VEGETARIAN'], 1, 250.00, 'INR', true),
    ('00000000-0000-4000-8000-000000000002', 'Dal Tadka',
     'Yellow lentils with a cumin and garlic tempering.', 'MAIN',
     ARRAY['lentils', 'cumin', 'garlic', 'ghee'], ARRAY['VEGETARIAN'], 2, 180.50, 'INR', true),
    ('00000000-0000-4000-8000-000000000003', 'Chicken Biryani',
     'Basmati rice layered with spiced chicken.', 'MAIN',
     ARRAY['chicken', 'basmati rice', 'yogurt', 'spices'], ARRAY[]::text[], 3, 320.00, 'INR', true),
    ('00000000-0000-4000-8000-000000000004', 'Vegetable Pulao',
     'Mild basmati rice with vegetables and whole spices.', 'RICE',
     ARRAY['basmati rice', 'carrot', 'peas', 'oil', 'spices'], ARRAY['VEGETARIAN', 'VEGAN'], 1, 160.00, 'INR', true),
    ('00000000-0000-4000-8000-000000000005', 'Butter Naan',
     'Tandoor-baked wheat bread brushed with butter.', 'BREAD',
     ARRAY['wheat flour', 'yogurt', 'butter'], ARRAY['VEGETARIAN'], 0, 45.00, 'INR', true),
    ('00000000-0000-4000-8000-000000000006', 'Gulab Jamun',
     'Milk dumplings soaked in cardamom sugar syrup.', 'DESSERT',
     ARRAY['milk solids', 'wheat flour', 'sugar', 'cardamom'], ARRAY['VEGETARIAN'], 0, 90.00, 'INR', false);
