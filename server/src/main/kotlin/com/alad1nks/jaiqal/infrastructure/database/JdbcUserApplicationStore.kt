package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.api.contract.UpdatePlantRequest
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.plants.PlantRecord
import com.alad1nks.jaiqal.users.SessionRecord
import com.alad1nks.jaiqal.users.UserApplicationStore
import com.alad1nks.jaiqal.users.UserRecord
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class JdbcUserApplicationStore(private val dataSource: DataSource) : UserApplicationStore {
    override fun createUser(user: UserRecord) = tx { c ->
        c.prepareStatement("INSERT INTO users(id,email,password_hash,created_at) VALUES (?,?,?,?) ON CONFLICT(email) DO NOTHING").use {
            it.setObject(1,user.id); it.setString(2,user.email); it.setString(3,user.passwordHash); it.setObject(4,user.createdAt); it.executeUpdate() == 1
        }
    }
    override fun findUserByEmail(email: String) = queryOne("SELECT * FROM users WHERE email=?", email, mapper = ResultSet::user)
    override fun createSession(session: SessionRecord) = tx { c -> c.insertSession(session, session.user); Unit }
    override fun rotateSession(oldHash: String, replacement: SessionRecord) = tx { c ->
        val user = c.prepareStatement("""SELECT u.* FROM refresh_tokens r JOIN users u ON u.id=r.user_id WHERE r.token_hash=? AND r.revoked_at IS NULL AND r.expires_at>now() FOR UPDATE OF r""").use {
            it.setString(1,oldHash); it.executeQuery().use { rs -> if(rs.next()) rs.user() else null }
        } ?: return@tx null
        c.insertSession(replacement, user)
        c.prepareStatement("UPDATE refresh_tokens SET revoked_at=now(), replaced_by_id=? WHERE token_hash=? AND revoked_at IS NULL").use {
            it.setObject(1,replacement.id); it.setString(2,oldHash); check(it.executeUpdate()==1)
        }
        user
    }
    override fun revokeSession(hash: String, userId: UUID?) = tx { c ->
        c.prepareStatement("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()").use {
            it.setString(1,hash); it.setObject(2,userId); it.executeUpdate()==1
        }
    }
    override fun listPlants(userId: UUID) = query("SELECT * FROM plants WHERE user_id=? AND archived_at IS NULL ORDER BY created_at", userId, mapper = ResultSet::plant)
    override fun findPlant(userId: UUID, plantId: UUID) = queryOne("SELECT * FROM plants WHERE id=? AND user_id=? AND archived_at IS NULL", plantId, userId, mapper=ResultSet::plant)
    override fun createPlant(plant: PlantRecord) = tx { c ->
        c.prepareStatement("INSERT INTO plants(id,user_id,name,species,image_url,created_at) VALUES(?,?,?,?,?,?)").use {
            it.setObject(1,plant.id);it.setObject(2,plant.userId);it.setString(3,plant.name);it.setString(4,plant.species);it.setString(5,plant.imageUrl);it.setObject(6,plant.createdAt);it.executeUpdate()
        }; plant
    }
    override fun updatePlant(userId: UUID, plantId: UUID, request: UpdatePlantRequest) = tx { c ->
        c.prepareStatement("""UPDATE plants SET name=COALESCE(?,name),species=COALESCE(?,species),image_url=COALESCE(?,image_url) WHERE id=? AND user_id=? AND archived_at IS NULL RETURNING *""").use {
            it.setString(1,request.name?.trim());it.setString(2,request.species?.trim());it.setString(3,request.imageUrl?.trim());it.setObject(4,plantId);it.setObject(5,userId)
            it.executeQuery().use { rs -> if(rs.next()) rs.plant() else null }
        }
    }
    override fun archivePlant(userId: UUID, plantId: UUID, at: OffsetDateTime) = update("UPDATE plants SET archived_at=? WHERE id=? AND user_id=? AND archived_at IS NULL", at, plantId, userId)==1

    override fun claimDevice(userId: UUID, plantId: UUID, claimHash: String, now: OffsetDateTime) = tx { c ->
        val deviceId = c.prepareStatement("""SELECT cc.device_id FROM device_claim_codes cc JOIN devices d ON d.id=cc.device_id JOIN plants p ON p.id=? AND p.user_id=? AND p.archived_at IS NULL WHERE cc.code_hash=? AND cc.consumed_at IS NULL AND cc.expires_at>? AND d.plant_id IS NULL AND d.disabled_at IS NULL FOR UPDATE OF cc,d""").use {
            it.setObject(1,plantId);it.setObject(2,userId);it.setString(3,claimHash);it.setObject(4,now);it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null }
        } ?: return@tx null
        c.prepareStatement("UPDATE device_claim_codes SET consumed_at=? WHERE code_hash=?").use { it.setObject(1,now);it.setString(2,claimHash);it.executeUpdate() }
        c.prepareStatement("UPDATE devices SET plant_id=? WHERE id=? RETURNING *").use { it.setObject(1,plantId);it.setObject(2,deviceId);it.executeQuery().use { rs -> rs.next(); rs.device() } }
    }
    override fun listDevices(userId: UUID) = query("SELECT d.* FROM devices d JOIN plants p ON p.id=d.plant_id WHERE p.user_id=? AND p.archived_at IS NULL ORDER BY d.created_at", userId, mapper=ResultSet::device)
    override fun findDevice(userId: UUID, deviceId: UUID) = queryOne("SELECT d.* FROM devices d JOIN plants p ON p.id=d.plant_id WHERE d.id=? AND p.user_id=? AND p.archived_at IS NULL", deviceId,userId,mapper=ResultSet::device)
    override fun updateDevice(userId: UUID, deviceId: UUID, name: String?, plantId: UUID?) = tx { c ->
        c.prepareStatement("""UPDATE devices d SET name=COALESCE(?,d.name),plant_id=COALESCE(?,d.plant_id) WHERE d.id=? AND EXISTS(SELECT 1 FROM plants current WHERE current.id=d.plant_id AND current.user_id=? AND current.archived_at IS NULL) AND (? IS NULL OR EXISTS(SELECT 1 FROM plants target WHERE target.id=? AND target.user_id=? AND target.archived_at IS NULL)) RETURNING d.*""").use {
            it.setString(1,name);it.setObject(2,plantId);it.setObject(3,deviceId);it.setObject(4,userId);it.setObject(5,plantId);it.setObject(6,plantId);it.setObject(7,userId)
            it.executeQuery().use { rs -> if(rs.next()) rs.device() else null }
        }
    }
    override fun updateCalibration(userId: UUID, deviceId: UUID, dry: Int, wet: Int) = ownedDeviceUpdate(userId,deviceId,"soil_dry_raw=?,soil_wet_raw=?",dry,wet)
    override fun rotateDeviceToken(userId: UUID, deviceId: UUID, tokenHash: String) = ownedDeviceUpdate(userId,deviceId,"token_hash=?",tokenHash)

    private fun ownedDeviceUpdate(userId: UUID, deviceId: UUID, set: String, vararg values: Any) = tx { c ->
        c.prepareStatement("UPDATE devices d SET $set WHERE d.id=? AND EXISTS(SELECT 1 FROM plants p WHERE p.id=d.plant_id AND p.user_id=? AND p.archived_at IS NULL) RETURNING d.*").use { ps ->
            values.forEachIndexed { i,v -> ps.setObject(i+1,v) };ps.setObject(values.size+1,deviceId);ps.setObject(values.size+2,userId)
            ps.executeQuery().use { rs -> if(rs.next()) rs.device() else null }
        }
    }
    private fun update(sql: String, vararg values: Any?) = tx { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
    private fun <T> query(sql: String, vararg values: Any?, mapper: (ResultSet) -> T): List<T> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { results -> buildList { while (results.next()) add(mapper(results)) } }
            }
        }
    private fun <T> queryOne(sql: String, vararg values: Any?, mapper: (ResultSet) -> T) = query(sql, *values, mapper = mapper).singleOrNull()
    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        try { block(connection).also { connection.commit() } } catch (failure: Throwable) { connection.rollback(); throw failure }
    }
}

private fun Connection.insertSession(s:SessionRecord,user:UserRecord){prepareStatement("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,now())").use{it.setObject(1,s.id);it.setObject(2,user.id);it.setString(3,s.tokenHash);it.setObject(4,s.expiresAt);it.executeUpdate()}}
private fun ResultSet.user()=UserRecord(getObject("id",UUID::class.java),getString("email"),getString("password_hash"),getObject("created_at",OffsetDateTime::class.java))
private fun ResultSet.plant()=PlantRecord(getObject("id",UUID::class.java),getObject("user_id",UUID::class.java),getString("name"),getString("species"),getString("image_url"),getObject("created_at",OffsetDateTime::class.java),getObject("archived_at",OffsetDateTime::class.java))
private fun ResultSet.device()=DeviceRecord(getObject("id",UUID::class.java),getObject("plant_id",UUID::class.java),getString("name"),getString("token_hash"),getString("firmware_version"),getObject("last_seen_at",OffsetDateTime::class.java),getObject("soil_dry_raw") as Int?,getObject("soil_wet_raw") as Int?,getObject("disabled_at",OffsetDateTime::class.java),getObject("created_at",OffsetDateTime::class.java))
