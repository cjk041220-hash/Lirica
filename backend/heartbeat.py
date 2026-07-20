"""心跳调度器 —— 定时触发主动消息检查。"""
from apscheduler.schedulers.asyncio import AsyncIOScheduler

scheduler = AsyncIOScheduler()


async def check_and_proactive_message():
    """占位：检查是否需要主动给冷冷发消息。"""
    print("心跳触发：检查是否需要主动发消息...")
