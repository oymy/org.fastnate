CREATE DATABASE fastnate
GO
CREATE LOGIN fastnate WITH PASSWORD=N'fastnate', DEFAULT_DATABASE=fastnate, CHECK_EXPIRATION=OFF, CHECK_POLICY=OFF
GO
USE fastnate
GO
CREATE USER fastnate FOR LOGIN fastnate WITH DEFAULT_SCHEMA=[dbo]
GO
