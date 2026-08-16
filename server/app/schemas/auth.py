from pydantic import BaseModel


class SuiteLoginRequest(BaseModel):
    """The RS256 access token issued by dragonfly-id, presented by the Android AppAuth client."""

    suite_token: str


class RefreshRequest(BaseModel):
    """A refresh token previously minted by /auth/suite (or a prior /auth/refresh)."""

    refresh_token: str


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class UserOut(BaseModel):
    id: str
    email: str
    name: str

    model_config = {"from_attributes": True}
