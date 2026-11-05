class Failure(Exception):
    """A protocol status with a short ASCII code. Never carries sheet data."""

    def __init__(self, status, code):
        super().__init__(f"{status}/{code}")
        self.status = status
        self.code = code
