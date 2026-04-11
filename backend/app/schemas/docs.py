"""Documentation generation schemas."""

import datetime
from pydantic import BaseModel


class DocsStatus(BaseModel):
    generated: bool = False
    last_built: datetime.datetime | None = None
    page_count: int = 0
    output_dir: str = ""
    index_url: str | None = None


class DocsBuildEvent(BaseModel):
    step: str
    message: str
    progress: float
