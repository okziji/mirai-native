/*
 *
 * Mirai Native
 *
 * Copyright (C) 2020 iTX Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author PeratX
 * @website https://github.com/iTXTech/mirai-native
 *
 */

package org.itxtech.mirainative.bridge

import io.ktor.util.InternalAPI
import io.ktor.util.decodeBase64Bytes
import io.ktor.util.encodeBase64
import kotlinx.coroutines.launch
import kotlinx.io.core.*
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.event.events.MemberJoinRequestEvent
import net.mamoe.mirai.event.events.NewFriendRequestEvent
import net.mamoe.mirai.getFriendOrNull
import net.mamoe.mirai.getGroupOrNull
import net.mamoe.mirai.message.data.isAboutGroup
import net.mamoe.mirai.message.data.quote
import net.mamoe.mirai.utils.MiraiLogger
import org.itxtech.mirainative.Bridge
import org.itxtech.mirainative.MiraiNative
import org.itxtech.mirainative.manager.CacheManager
import org.itxtech.mirainative.manager.PluginManager
import org.itxtech.mirainative.message.ChainCodeConverter
import org.itxtech.mirainative.plugin.FloatingWindowEntry
import org.itxtech.mirainative.plugin.NativePlugin
import java.io.File
import java.nio.charset.Charset
import kotlin.io.use
import kotlin.text.toByteArray

@OptIn(InternalAPI::class)
object MiraiBridge {
    @JvmStatic
    fun quoteMessage(msgId: Int, message: String): Int {
        val internalId = CacheManager.nextId()
        MiraiNative.launch {
            val src = CacheManager.getMessage(msgId)
            if (src != null) {
                if (!src.isAboutGroup()) {
                    if (src.fromId != MiraiNative.bot.id) {
                        val f = MiraiNative.bot.getFriend(src.fromId)
                        f.sendMessage(src.quote() + ChainCodeConverter.codeToChain(message, f)).apply {
                            CacheManager.cacheMessage(source, internalId)
                        }
                    }
                } else {
                    val group = MiraiNative.bot.getGroup(src.targetId)
                    if (src.fromId != MiraiNative.bot.id) {
                        group.sendMessage(src.quote() + ChainCodeConverter.codeToChain(message, group)).apply {
                            CacheManager.cacheMessage(source, internalId)
                        }
                    }
                }
            }
        }
        return internalId
    }

    @JvmStatic
    fun sendPrivateMessage(id: Long, message: String): Int {
        val internalId = CacheManager.nextId()
        MiraiNative.launch {
            val contact = MiraiNative.bot.getFriendOrNull(id) ?: CacheManager.findMember(id)
            contact?.sendMessage(ChainCodeConverter.codeToChain(message, contact))?.apply {
                CacheManager.cacheMessage(source, internalId)
            }
        }
        return internalId
    }

    @JvmStatic
    fun sendGroupMessage(id: Long, message: String): Int {
        val internalId = CacheManager.nextId()
        MiraiNative.launch {
            val contact = MiraiNative.bot.getGroup(id)
            contact.sendMessage(ChainCodeConverter.codeToChain(message, contact)).apply {
                CacheManager.cacheMessage(source, internalId)
            }
        }
        return internalId
    }

    @JvmStatic
    fun setGroupBan(groupId: Long, memberId: Long, duration: Int): Int {
        MiraiNative.launch {
            if (duration == 0) {
                MiraiNative.bot.getGroup(groupId)[memberId].unmute()
            } else {
                MiraiNative.bot.getGroup(groupId)[memberId].mute(duration)
            }
        }
        return 0
    }

    @JvmStatic
    fun setGroupCard(groupId: Long, memberId: Long, card: String): Int {
        MiraiNative.bot.getGroup(groupId)[memberId].nameCard = card
        return 0
    }

    @JvmStatic
    fun setGroupKick(groupId: Long, memberId: Long): Int {
        MiraiNative.launch {
            MiraiNative.bot.getGroup(groupId)[memberId].kick()
        }
        return 0
    }

    @JvmStatic
    fun setGroupLeave(groupId: Long): Int {
        MiraiNative.launch {
            MiraiNative.bot.getGroup(groupId).quit()
        }
        return 0
    }

    @JvmStatic
    fun setGroupSpecialTitle(group: Long, member: Long, title: String, duration: Long): Int {
        MiraiNative.bot.getGroup(group)[member].specialTitle = title
        return 0
    }

    @JvmStatic
    fun setGroupWholeBan(group: Long, enable: Boolean): Int {
        MiraiNative.bot.getGroup(group).settings.isMuteAll = enable
        return 0
    }

    @JvmStatic
    fun getStrangerInfo(account: Long): String {
        val m = CacheManager.findMember(account) ?: return ""
        return buildPacket {
            writeLong(m.id)
            writeString(m.nick)
            writeInt(0) // TODO: 性别
            writeInt(0) // TODO: 年龄
        }.readBytes().encodeBase64()
    }

    private inline fun BytePacketBuilder.writeShortLVPacket(
        lengthOffset: ((Long) -> Long) = { it },
        builder: BytePacketBuilder.() -> Unit
    ): Int =
        BytePacketBuilder().apply(builder).build().use {
            val length = lengthOffset.invoke(it.remaining)
            writeShort(length.toShort())
            writePacket(it)
            return length.toInt()
        }


    private fun BytePacketBuilder.writeString(string: String) {
        val b = string.toByteArray(Charset.forName("GB18030"))
        writeShort(b.size.toShort())
        writeFully(b)
    }

    private fun BytePacketBuilder.writeBool(bool: Boolean) {
        writeInt(if (bool) 1 else 0)
    }

    private fun BytePacketBuilder.writeMember(member: Member) {
        writeLong(member.group.id)
        writeLong(member.id)
        writeString(member.nick)
        writeString(member.nameCard)
        writeInt(0) // TODO: 性别
        writeInt(0) // TODO: 年龄
        writeString("未知") // TODO: 地区
        writeInt(0) // TODO: 加群时间
        writeInt(0) // TODO: 最后发言
        writeString("") // TODO: 等级名称
        writeInt(
            when (member.permission) {
                MemberPermission.MEMBER -> 1
                MemberPermission.ADMINISTRATOR -> 2
                MemberPermission.OWNER -> 3
            }
        )
        writeBool(false) // TODO: 不良记录成员
        writeString(member.specialTitle)
        writeInt(-1) // TODO: 头衔过期时间
        writeBool(true) // TODO: 允许修改名片
    }

    @JvmStatic
    fun getFriendList(): String {
        val list = MiraiNative.bot.friends
        return buildPacket {
            writeInt(list.size)
            list.forEach { qq ->
                writeShortLVPacket {
                    writeLong(qq.id)
                    writeString(qq.nick)
                    //TODO: 备注
                    writeString("")
                }
            }
        }.readBytes().encodeBase64()
    }

    @JvmStatic
    fun getGroupInfo(id: Long): String {
        val info = MiraiNative.bot.getGroupOrNull(id)
        if (info != null) {
            return buildPacket {
                writeLong(id)
                writeString(info.name)
                writeInt(info.members.size + 1)
                //TODO: 上限
                writeInt(1000)
            }.readBytes().encodeBase64()
        }
        return ""
    }

    @JvmStatic
    fun getGroupList(): String {
        val list = MiraiNative.bot.groups
        return buildPacket {
            writeInt(list.size)
            list.forEach {
                writeShortLVPacket {
                    writeLong(it.id)
                    writeString(it.name)
                }
            }
        }.readBytes().encodeBase64()
    }

    @JvmStatic
    fun getGroupMemberInfo(groupId: Long, memberId: Long): String {
        val member = MiraiNative.bot.getGroupOrNull(groupId)?.getOrNull(memberId) ?: return ""
        return buildPacket {
            writeMember(member)
        }.readBytes().encodeBase64()
    }

    @JvmStatic
    fun getGroupMemberList(groupId: Long): String {
        val group = MiraiNative.bot.getGroupOrNull(groupId) ?: return ""
        return buildPacket {
            writeInt(group.members.size)
            group.members.forEach {
                writeShortLVPacket {
                    writeMember(it)
                }
            }
        }.readBytes().encodeBase64()
    }

    @JvmStatic
    fun setGroupAddRequest(requestId: String, reqType: Int, type: Int, reason: String): Int {
        MiraiNative.nativeLaunch {
            (CacheManager.getEvent(requestId) as? MemberJoinRequestEvent)?.apply {
                when (type) {//1通过，2拒绝，3忽略
                    1 -> accept()
                    2 -> reject()
                    3 -> ignore()
                }
            }
        }
        return 0
    }

    @JvmStatic
    fun setFriendAddRequest(requestId: String, type: Int, remark: String): Int {
        MiraiNative.nativeLaunch {
            (CacheManager.getEvent(requestId) as? NewFriendRequestEvent)?.apply {
                when (type) {//1通过，2拒绝
                    1 -> accept()
                    2 -> reject()
                }
            }
        }
        return 0
    }

    @JvmStatic
    fun addLog(pluginId: Int, priority: Int, type: String, content: String) {
        NativeLoggerHelper.log(PluginManager.plugins[pluginId]!!, priority, type, content)
    }

    @JvmStatic
    fun getPluginDataDir(pluginId: Int): String {
        return PluginManager.plugins[pluginId]!!.appDir.absolutePath + File.separatorChar
    }

    @JvmStatic
    fun getLoginQQ(): Long {
        return MiraiNative.bot.id
    }

    @JvmStatic
    fun getLoginNick(): String {
        return MiraiNative.bot.nick
    }

    private fun ByteReadPacket.readString(): String {
        return String(readBytes(readShort().toInt()))
    }

    fun updateFwe(pluginId: Int, fwe: FloatingWindowEntry) {
        val pk = ByteReadPacket(
            Bridge.callStringMethod(pluginId, fwe.status.function).decodeBase64Bytes()
        )
        fwe.data = pk.readString()
        fwe.unit = pk.readString()
        fwe.color = pk.readInt()
    }
}

internal object NativeLoggerHelper {
    const val LOG_DEBUG = 0
    const val LOG_INFO = 10
    const val LOG_INFO_SUCC = 11
    const val LOG_INFO_RECV = 12
    const val LOG_INFO_SEND = 13
    const val LOG_WARNING = 20
    const val LOG_ERROR = 21
    const val LOG_FATAL = 22

    private fun getLogger(): MiraiLogger {
        return MiraiNative.logger
    }

    fun log(plugin: NativePlugin, priority: Int, type: String, content: String) {
        var c = "[NP " + plugin.identifier
        if ("" != type) {
            c += " $type"
        }
        c += "] $content"
        when (priority) {
            LOG_DEBUG -> getLogger().debug(c)
            LOG_INFO, LOG_INFO_RECV, LOG_INFO_SUCC, LOG_INFO_SEND -> getLogger().info(
                c
            )
            LOG_WARNING -> getLogger().warning(c)
            LOG_ERROR -> getLogger().error(c)
            LOG_FATAL -> getLogger().error("[FATAL] $c")
        }
    }
}