from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.config import settings
from app.db.session import create_schema
from app.routers.events import router as events_router
from app.routers.health import router as health_router


@asynccontextmanager
async def lifespan(_: FastAPI):
    await create_schema()
    yield


app = FastAPI(title="LoveACE Analytics", version="0.1.0", lifespan=lifespan)
app.include_router(health_router)
app.include_router(events_router)


@app.get("/")
async def root():
    return {"name": settings.app_name, "ok": True}
