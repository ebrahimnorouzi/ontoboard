"""Version management schemas."""

from pydantic import BaseModel


class VersionInfo(BaseModel):
    version_iri: str | None = None
    version: str | None = None
    prior_version: str | None = None


class VersionUpdate(BaseModel):
    version_iri: str | None = None
    version: str | None = None
    bump: str | None = None  # major | minor | patch
